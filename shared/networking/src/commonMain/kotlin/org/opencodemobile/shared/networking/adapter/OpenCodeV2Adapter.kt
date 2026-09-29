package org.opencodemobile.shared.networking.adapter

import io.ktor.client.HttpClient
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import org.opencode.mobile.networking.client.generated.apis.OpenCodeApiClient
import org.opencode.mobile.networking.client.generated.models.ApiAgent
import org.opencode.mobile.networking.client.generated.models.ApiProvider
import org.opencode.mobile.networking.client.generated.models.ApiQuestionReplyRequest
import org.opencode.mobile.networking.client.generated.models.ApiQuestionRequest
import org.opencodemobile.shared.domain.connection.ConnectionHandshake
import org.opencodemobile.shared.domain.connection.OpenCodeGateway
import org.opencodemobile.shared.domain.connection.ServerCredential
import org.opencodemobile.shared.domain.connection.ServerHealth
import org.opencodemobile.shared.domain.connection.ServerIdentityCheck
import org.opencodemobile.shared.domain.connection.ServerIdentityException
import org.opencodemobile.shared.domain.connection.ServerProfile
import org.opencodemobile.shared.domain.interaction.AgentDescriptor
import org.opencodemobile.shared.domain.interaction.InteractionNotConnectedException
import org.opencodemobile.shared.domain.interaction.ModelDescriptor
import org.opencodemobile.shared.domain.interaction.OpenCodeInteractionGateway
import org.opencodemobile.shared.domain.interaction.PendingQuestion
import org.opencodemobile.shared.domain.interaction.ProviderModels
import org.opencodemobile.shared.domain.interaction.QuestionItem
import org.opencodemobile.shared.domain.interaction.QuestionOption
import org.opencodemobile.shared.domain.interaction.ServerCatalog
import org.opencodemobile.shared.security.identity.ServerIdentityAuthorization
import org.opencodemobile.shared.security.identity.ServerIdentityGate
import org.opencodemobile.shared.security.identity.ServerIdentityPinController

/**
 * The sole consumer of the generated OpenAPI client (Rule R3 / ADR-0002).
 *
 * Its connection path is also the T1 enforcement point
 * (`docs/ARCHITECTURE.md` §"Server identity verification (T1)"):
 *
 * 1. [ServerIdentityGate] observes the server's presented identity and decides
 *    whether the credential may be released. A first contact or a changed
 *    fingerprint throws [ServerIdentityException] **before** any request is
 *    built.
 * 2. Only on [ServerIdentityAuthorization.Authorized] is the credential permit
 *    set. The generated client's auth provider reads that permit, so the
 *    `Authorization` header cannot be attached by a caller that skipped the
 *    check, and cannot survive [disconnect].
 * 3. The platform TLS engine additionally enforces the pin during the
 *    handshake (defense in depth), so even a future bug that reorders the two
 *    steps cannot send the credential over an unverified connection. [identityPin]
 *    is required and must be the same instance passed to
 *    [createOpenCodeHttpClient]/`OpenCodeHttpClient.create`, otherwise this
 *    backstop silently disappears.
 *
 * The adapter holds a single active connection: [connect] starts by clearing any
 * previous permit, and the permit state is process-global. Concurrent [connect]
 * calls on one instance are not supported.
 */
public class OpenCodeV2Adapter(
    private val httpClient: HttpClient,
    private val identityGate: ServerIdentityGate,
    private val identityPin: ServerIdentityPinController,
) : OpenCodeGateway, OpenCodeInteractionGateway {

    @Volatile
    private var credentialPermit: ServerCredential? = null

    @Volatile
    private var authorizationsEnabled: Boolean = false

    @Volatile
    private var plaintextWarning: String? = null

    /**
     * The generated client bound to the active connection, or null. It is the
     * V1-07/V1-08/V1-09 interaction surface: null means "not connected", and
     * every interaction call then fails closed instead of sending anything.
     */
    @Volatile
    private var activeClient: OpenCodeApiClient? = null

    override suspend fun connect(
        profile: ServerProfile,
        credential: ServerCredential?,
    ): ConnectionHandshake {
        disconnect()

        val identityCheck = when (val authorization = identityGate.authorize(profile)) {
            is ServerIdentityAuthorization.Authorized -> {
                identityPin.setExpectedPin(authorization.fingerprint)
                plaintextWarning = authorization.plaintextWarning
                if (authorization.plaintextWarning != null) {
                    ServerIdentityCheck.PlaintextHttp
                } else {
                    ServerIdentityCheck.Trusted(authorization.fingerprint!!)
                }
            }

            is ServerIdentityAuthorization.ConfirmationRequired ->
                throw ServerIdentityException.ConfirmationRequired(authorization.presented)

            is ServerIdentityAuthorization.Blocked ->
                throw ServerIdentityException.IdentityChanged(
                    previous = authorization.previous,
                    presented = authorization.presented,
                )
        }

        // Identity is verified: only now may the credential be released.
        credentialPermit = credential
        authorizationsEnabled = true

        val client = OpenCodeApiClient(
            baseUrl = profile.baseUrl,
            httpClient = httpClient,
            authTokenProvider = { currentCredential() },
        )
        activeClient = client

        return try {
            val dto = client.getHealth()
            ConnectionHandshake(
                profileId = profile.id,
                health = ServerHealth(healthy = dto.healthy, version = dto.version),
                identity = identityCheck,
            )
        } catch (failure: Throwable) {
            disconnect()
            throw failure
        }
    }

    override fun disconnect() {
        authorizationsEnabled = false
        credentialPermit = null
        plaintextWarning = null
        activeClient = null
        identityPin.reset()
    }

    /**
     * The token the generated client may attach, or null. Returning null when
     * no verified connection is active makes the failure mode "no credential
     * sent" rather than "credential leaked".
     */
    private fun currentCredential(): String? =
        if (authorizationsEnabled) credentialPermit?.bearerToken else null

    /** Non-null when the active connection is plaintext HTTP; must be shown as text (T1). */
    public fun plaintextWarningForActiveConnection(): String? = plaintextWarning

    // --- V1-07: pending agent questions ---

    override suspend fun pendingQuestions(directory: String?): List<PendingQuestion> =
        requireActiveClient().listQuestions(directory).map { it.toDomain() }

    override suspend fun answerQuestion(
        requestId: String,
        answers: List<List<String>>,
        directory: String?,
    ) {
        requireActiveClient().replyQuestion(
            requestID = requestId,
            request = ApiQuestionReplyRequest(answers = answers),
            directory = directory,
        )
    }

    override suspend fun rejectQuestion(requestId: String, directory: String?) {
        requireActiveClient().rejectQuestion(requestID = requestId, directory = directory)
    }

    // --- V1-08: abort ---

    override suspend fun abortTurn(sessionId: String) {
        requireActiveClient().abortSession(sessionID = sessionId)
    }

    // --- V1-09: server-exposed models and agents ---

    override suspend fun serverCatalog(directory: String?): ServerCatalog {
        val client = requireActiveClient()
        // Each surface is read independently: an older server that does not
        // expose one of them must not hide the other, and neither may ever be
        // replaced by a built-in catalog (V1-09).
        val providers = bestEffort { client.listProviders(directory) }
        val agents = bestEffort { client.listAgents(directory) }
        return ServerCatalog(
            providers = providers?.all.orEmpty().map { it.toDomain() },
            defaultModelByProvider = providers?.default.orEmpty(),
            agents = agents.orEmpty().map { it.toDomain() },
            providersAvailable = providers != null,
            agentsAvailable = agents != null,
        )
    }

    /**
     * Fails closed when there is no verified active connection: the credential
     * permit is released only after the T1 identity check, so without it there
     * is nothing legitimate to send.
     */
    private fun requireActiveClient(): OpenCodeApiClient =
        activeClient ?: throw InteractionNotConnectedException()

    /** Runs [block], turning a failure into null but letting cancellation propagate. */
    private suspend fun <T> bestEffort(block: suspend () -> T): T? =
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            null
        }

    /** Test/diagnostic hook: whether an active connection currently permits the credential. */
    internal fun isCredentialPermitActive(): Boolean = authorizationsEnabled && credentialPermit != null
}

private fun ApiQuestionRequest.toDomain(): PendingQuestion = PendingQuestion(
    id = id,
    sessionId = sessionID,
    questions = questions.map { info ->
        QuestionItem(
            question = info.question,
            header = info.header,
            options = info.options.map { QuestionOption(label = it.label, description = it.description) },
            multiple = info.multiple,
            custom = info.custom,
        )
    },
)

private fun ApiProvider.toDomain(): ProviderModels = ProviderModels(
    id = id,
    name = name,
    models = models.map { (key, model) ->
        ModelDescriptor(
            id = model.id.ifBlank { key },
            name = model.name,
            providerId = model.providerID ?: id,
        )
    }.sortedBy { it.id },
)

private fun ApiAgent.toDomain(): AgentDescriptor = AgentDescriptor(
    name = name,
    description = description,
    mode = mode,
    hidden = hidden ?: false,
)
