package org.opencodemobile.shared.networking.adapter

import io.ktor.client.HttpClient
import io.ktor.client.plugins.ClientRequestException
import io.ktor.http.HttpStatusCode
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.opencode.mobile.networking.client.generated.apis.OpenCodeApiClient
import org.opencode.mobile.networking.client.generated.models.ApiAgent
import org.opencode.mobile.networking.client.generated.models.ApiCreateSessionRequest
import org.opencode.mobile.networking.client.generated.models.ApiMessage
import org.opencode.mobile.networking.client.generated.models.ApiMessagePart
import org.opencode.mobile.networking.client.generated.models.ApiModelRef
import org.opencode.mobile.networking.client.generated.models.ApiPromptAsyncRequest
import org.opencode.mobile.networking.client.generated.models.ApiPromptPartInput
import org.opencode.mobile.networking.client.generated.models.ApiProvider
import org.opencode.mobile.networking.client.generated.models.ApiQuestionReplyRequest
import org.opencode.mobile.networking.client.generated.models.ApiQuestionRequest
import org.opencode.mobile.networking.client.generated.models.ApiSession
import org.opencode.mobile.networking.client.generated.models.ApiUpdateSessionRequest
import org.opencodemobile.shared.domain.chat.ChatEvent
import org.opencodemobile.shared.domain.chat.ChatEventDecoder
import org.opencodemobile.shared.domain.chat.ChatPrompt
import org.opencodemobile.shared.domain.chat.OpenCodeChatGateway
import org.opencodemobile.shared.domain.chat.TranscriptMessage
import org.opencodemobile.shared.domain.chat.TranscriptPart
import org.opencodemobile.shared.domain.chat.TranscriptRole
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
import org.opencodemobile.shared.domain.session.ServerUnavailableException
import org.opencodemobile.shared.domain.session.SessionCapabilities
import org.opencodemobile.shared.domain.session.SessionGateway
import org.opencodemobile.shared.domain.session.SessionNotConnectedException
import org.opencodemobile.shared.domain.session.SessionNotFoundException
import org.opencodemobile.shared.domain.session.SessionRejectedException
import org.opencodemobile.shared.domain.session.SessionSummary
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
) : OpenCodeGateway, OpenCodeInteractionGateway, SessionGateway, OpenCodeChatGateway, ChatEventDecoder {

    /** Payload decoding for the chat surface only; the domain stays JSON-free. */
    private val chatJson: Json = Json { ignoreUnknownKeys = true; isLenient = true }

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

    // --- V1-04: session list, create, resume, rename, delete, fork ---

    override suspend fun listSessions(directory: String?): List<SessionSummary> =
        sessionCall { requireActiveClient().listSessions(directory).map { it.toDomain() } }

    override suspend fun getSession(sessionId: String): SessionSummary =
        sessionCall(sessionId) { requireActiveClient().getSession(sessionId).toDomain() }

    override suspend fun createSession(directory: String?, title: String?): SessionSummary =
        sessionCall {
            requireActiveClient()
                // `directory` is a query parameter on POST /session; the request
                // body carries only title/parentID in the pinned spec.
                .createSession(
                    request = ApiCreateSessionRequest(title = title),
                    directory = directory,
                )
                .toDomain()
        }

    override suspend fun renameSession(sessionId: String, title: String): SessionSummary =
        sessionCall(sessionId) {
            requireActiveClient()
                .updateSession(sessionId, ApiUpdateSessionRequest(title = title))
                .toDomain()
        }

    override suspend fun deleteSession(sessionId: String) {
        sessionCall(sessionId) { requireActiveClient().deleteSession(sessionId) }
    }

    override suspend fun forkSession(sessionId: String): SessionSummary =
        sessionCall(sessionId) { requireActiveClient().forkSession(sessionId).toDomain() }

    /**
     * Reads the capability surface from the server's own published document
     * (`GET /doc`). A server that does not publish the fork route reports
     * [SessionCapabilities.Unknown] (fork unavailable); an unreadable surface
     * degrades to a hidden action rather than an error, which is what the
     * V1-04 "no rejected call" criterion requires.
     */
    override suspend fun sessionCapabilities(directory: String?): SessionCapabilities =
        try {
            val document = requireActiveClient().getServerDocument()
            SessionCapabilities(forkAvailable = ServerSurface.supportsFork(document))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            SessionCapabilities.Unknown
        }

    // --- V1-05: chat transcript + prompt ---

    override suspend fun transcript(sessionId: String): List<TranscriptMessage> =
        requireActiveClient().listMessages(sessionId).map { it.toDomain() }

    override suspend fun sendPrompt(sessionId: String, prompt: ChatPrompt) {
        requireActiveClient().sendPromptAsync(
            sessionID = sessionId,
            request = ApiPromptAsyncRequest(
                agent = prompt.agent,
                model = prompt.model?.let { ApiModelRef(providerID = it.providerId, modelID = it.modelId) },
                parts = listOf(ApiPromptPartInput(type = "text", text = prompt.text)),
            ),
        )
    }

    override fun decode(type: String, payload: String): ChatEvent? {
        val root = runCatching { chatJson.parseToJsonElement(payload) }.getOrNull() as? JsonObject
            ?: return null
        val declaredType = root.stringOrNull("type") ?: type
        val properties = root["properties"] as? JsonObject
            ?: root["data"] as? JsonObject
            ?: return null
        return when (declaredType) {
            "message.part.updated" -> decodePartUpdated(properties)
            "message.updated" -> decodeMessageUpdated(properties)
            "message.removed" -> decodeMessageRemoved(properties)
            else -> null
        }
    }

    private fun decodePartUpdated(properties: JsonObject): ChatEvent? {
        val messageId = properties.stringOrNull("messageID") ?: return null
        val part = (properties["part"] as? JsonObject)?.toTranscriptPartOrNull() ?: return null
        return ChatEvent.PartUpdated(
            messageId = messageId,
            sessionId = properties.stringOrNull("sessionID"),
            part = part,
        )
    }

    private fun decodeMessageUpdated(properties: JsonObject): ChatEvent? {
        val info = properties["info"] as? JsonObject
            ?: properties["message"] as? JsonObject
            ?: return null
        val message = info.toTranscriptMessageOrNull() ?: return null
        return ChatEvent.MessageUpdated(message)
    }

    private fun decodeMessageRemoved(properties: JsonObject): ChatEvent? {
        val messageId = properties.stringOrNull("messageID")
            ?: properties.stringOrNull("id")
            ?: return null
        return ChatEvent.MessageRemoved(
            messageId = messageId,
            sessionId = properties.stringOrNull("sessionID"),
        )
    }

    /**
     * Runs a session call and maps every failure to a typed
     * [org.opencodemobile.shared.domain.session.SessionFailure], so a raw status
     * code or generated error type never escapes the adapter. Cancellation is
     * always propagated.
     */
    private suspend fun <T> sessionCall(
        sessionId: String? = null,
        block: suspend () -> T,
    ): T =
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (notConnected: InteractionNotConnectedException) {
            throw SessionNotConnectedException()
        } catch (clientError: ClientRequestException) {
            if (clientError.response.status == HttpStatusCode.NotFound) {
                throw SessionNotFoundException(sessionId = sessionId ?: "", cause = clientError)
            }
            throw SessionRejectedException(
                message = clientError.message ?: "The server refused the session request",
                cause = clientError,
            )
        } catch (failure: Throwable) {
            throw ServerUnavailableException(cause = failure)
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

private fun ApiSession.toDomain(): SessionSummary = SessionSummary(
    id = id,
    // A session the server has not titled falls back to its id so the list
    // never renders a blank row.
    title = title?.takeIf { it.isNotBlank() } ?: id,
    directory = directory,
    projectId = projectID,
    parentSessionId = parentID,
    createdAt = time?.created ?: 0L,
    updatedAt = time?.updated ?: time?.created ?: 0L,
)

private fun ApiMessage.toDomain(): TranscriptMessage = TranscriptMessage(
    id = id,
    sessionId = sessionID,
    role = TranscriptRole.fromWire(role),
    parts = parts.mapNotNull { it.toDomain() },
)

private fun ApiMessagePart.toDomain(): TranscriptPart? {
    val partId = id ?: return null
    return TranscriptPart(id = partId, type = type, text = text.orEmpty())
}

private fun JsonObject.toTranscriptPartOrNull(): TranscriptPart? {
    val partId = stringOrNull("id") ?: return null
    return TranscriptPart(
        id = partId,
        type = stringOrNull("type") ?: "text",
        text = stringOrNull("text").orEmpty(),
        language = stringOrNull("language"),
    )
}

private fun JsonObject.toTranscriptMessageOrNull(): TranscriptMessage? {
    val messageId = stringOrNull("id") ?: return null
    val parts = (this["parts"] as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonObject)?.toTranscriptPartOrNull() }
    return TranscriptMessage(
        id = messageId,
        sessionId = stringOrNull("sessionID").orEmpty(),
        role = TranscriptRole.fromWire(stringOrNull("role")),
        parts = parts,
    )
}

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

/**
 * Reads optional capabilities off the server's published OpenAPI document.
 *
 * Capability detection is deliberately based on the surface the server
 * *publishes at runtime*, not on a hard-coded feature catalog: a server build
 * that does not expose `POST /session/{sessionID}/fork` simply omits the path.
 */
private object ServerSurface {
    private const val FORK_PATH = "/session/{sessionID}/fork"

    private val json: Json = Json { ignoreUnknownKeys = true }

    fun supportsFork(document: String): Boolean {
        val root = runCatching { json.parseToJsonElement(document) }.getOrNull() as? JsonObject
            ?: return false
        val paths = root["paths"] as? JsonObject ?: return false
        return paths.containsKey(FORK_PATH)
    }
}

