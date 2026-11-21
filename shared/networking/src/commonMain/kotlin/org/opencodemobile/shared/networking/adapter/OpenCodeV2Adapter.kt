package org.opencodemobile.shared.networking.adapter

import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.pluginOrNull
import kotlin.concurrent.Volatile
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import org.opencode.mobile.networking.client.generated.apis.OpenCodeApiClient
import org.opencode.mobile.networking.client.generated.models.*
import org.opencodemobile.shared.domain.chat.*
import org.opencodemobile.shared.domain.connection.*
import org.opencodemobile.shared.domain.event.EventTransport
import org.opencodemobile.shared.domain.interaction.*
import org.opencodemobile.shared.domain.session.*
import org.opencodemobile.shared.networking.permission.OpenCodePermissionGateway
import org.opencodemobile.shared.networking.realtime.NetworkingRealtimeTransport
import org.opencodemobile.shared.security.identity.ServerIdentityAuthorization
import org.opencodemobile.shared.security.identity.ServerIdentityGate
import org.opencodemobile.shared.security.identity.ServerIdentityPinController

/** V2 facade and T1 credential release boundary; generated types stay in networking (R3). */
@Suppress("TooManyFunctions")
public class OpenCodeV2Adapter(
    private val httpClient: HttpClient,
    private val identityGate: ServerIdentityGate,
    private val identityPin: ServerIdentityPinController,
    private val compatibilityProfile: CompatibilityProfile = CompatibilityProfile.OpenCodeServerV2,
    private val basicAuthController: BasicAuthCredentialController = BasicAuthCredentialController(),
) : OpenCodeGateway, OpenCodeInteractionGateway, SessionGateway, OpenCodeChatGateway, ChatEventDecoder {

    private val chatDecoder = AdapterChatDecoder()

    @Volatile
    private var credentialPermit: ServerCredential? = null

    @Volatile
    private var authorizationsEnabled: Boolean = false

    @Volatile
    private var plaintextWarning: String? = null

    /** No API call is permitted without an identity-verified connection. */
    @Volatile
    private var activeClient: OpenCodeApiClient? = null

    /** Convenience overload for connections without reverse-proxy credentials. */
    public suspend fun connect(profile: ServerProfile, credential: ServerCredential?): ConnectionHandshake =
        connect(profile, credential, basicAuth = null)

    @Suppress("LongMethod", "CyclomaticComplexMethod", "TooGenericExceptionCaught", "NestedBlockDepth")
    override suspend fun connect(
        profile: ServerProfile,
        credential: ServerCredential?,
        basicAuth: BasicAuthCredential?,
    ): ConnectionHandshake {
        disconnect()

        // 1. Connection policy: fail closed before any request or credential release.
        //    A plaintext HTTP profile aimed at a public host is rejected here.
        val scope = when (val policy = HttpConnectionPolicy.decide(profile)) {
            is HttpConnectionPolicyDecision.Rejected ->
                throw ConnectionPolicyException.Rejected(policy)

            is HttpConnectionPolicyDecision.Allowed -> policy.scope
        }

        // 2. Identity is verified: only now may the credential be released.
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
        // Same release point for the reverse-proxy credential: a connection that
        // fails the identity check above never reaches this line, so the Basic
        // Auth credential cannot leak over an unverified connection either.
        basicAuthController.setCredential(basicAuth)

        val client = OpenCodeApiClient(
            baseUrl = profile.baseUrl,
            httpClient = httpClient,
            authTokenProvider = { currentCredential() },
        )
        activeClient = client

        return try {
            // 3. Info probe: the first request on the connection. Retried once on
            //    a transport failure while still connecting, because the Darwin
            //    engine reports both a job-driven cancellation (scope torn down
            //    mid-request) and a one-off transport hiccup (e.g. a tailnet
            //    tunnel still negotiating) the same way: a plain
            //    DarwinHttpRequestException (NSURLErrorCancelled), not a
            //    CancellationException.
            val info = try {
                client.getInfo()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (firstFailure: Throwable) {
                if (!coroutineContext.isActive) throw CancellationException("Connect was cancelled", firstFailure)
                try {
                    client.getInfo()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (secondFailure: Throwable) {
                    if (!coroutineContext.isActive) {
                        throw CancellationException("Connect was cancelled", secondFailure)
                    }
                    throw HandshakeException.InfoUnavailable(secondFailure)
                }
            }

            // 4. Version gate: an absent or unparseable version is incomplete,
            //    never implicitly compatible.
            val version = ServerVersion.parseOrNull(info.version)
                ?: throw HandshakeException.Incomplete(
                    "GET /api/info returned no parseable version ('${info.version}')",
                )

            when (val compatibility = compatibilityProfile.evaluate(version)) {
                is CompatibilityResult.Compatible -> ConnectionHandshake(
                    profileId = profile.id,
                    identity = identityCheck,
                    version = compatibility.version,
                    scope = scope,
                )

                is CompatibilityResult.Incompatible ->
                    throw HandshakeException.Incompatible(compatibility.serverVersion, compatibility.profile)

                CompatibilityResult.Unknown ->
                    throw HandshakeException.Incomplete(
                        "GET /api/info returned no parseable version ('${info.version}')",
                    )
            }
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
        basicAuthController.reset()
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

    override suspend fun pendingQuestions(directory: String?): List<PendingQuestion> =
        requireActiveClient().listForms().data.map { it.toDomain() }

    override suspend fun answerQuestion(
        sessionId: String, requestId: String, answer: FormAnswer, directory: String?,
    ) {
        requireActiveClient().replyForm(sessionId, requestId, ApiFormReplyRequest(answer.toApi()))
    }

    override suspend fun rejectQuestion(sessionId: String, requestId: String, directory: String?) {
        requireActiveClient().cancelForm(sessionId, requestId)
    }

    override suspend fun abortTurn(sessionId: String, directory: String?) {
        requireActiveClient().interruptSession(sessionId)
    }

    override suspend fun serverCatalog(directory: String?): ServerCatalog {
        val client = requireActiveClient()
        val providers = optionalSurface { client.listProviders().data }
        val models = optionalSurface { client.listModels().data }
        val agents = optionalSurface { client.listAgents().data }
        return ServerCatalog(
            providers = providers.orEmpty().map { provider ->
                ProviderModels(provider.id, provider.name,
                    models.orEmpty().filter { it.providerID == provider.id }.map { it.toDomain() }.sortedBy { it.id })
            },
            agents = agents.orEmpty().map { it.toDomain() },
            providersAvailable = providers != null && models != null,
            agentsAvailable = agents != null,
        )
    }

    override suspend fun listSessions(directory: String?): List<SessionSummary> = sessionCall {
        val client = requireActiveClient()
        val result = mutableListOf<SessionSummary>()
        val seen = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val page = client.listSessions(directory = directory, cursor = cursor)
            result.addAll(page.data.map { it.toDomain() })
            cursor = page.cursor.next
            check(cursor == null || seen.add(cursor)) { "Repeated session pagination cursor" }
        } while (cursor != null)
        result.distinctBy { it.id }
    }

    override suspend fun getSession(sessionId: String): SessionSummary =
        sessionCall(sessionId) { requireActiveClient().getSession(sessionId).data.toDomain() }

    override suspend fun createSession(directory: String?, title: String?): SessionSummary = sessionCall {
        requireActiveClient().createSession(ApiCreateSessionRequest(
            title = title, location = directory?.let { ApiLocation(it) },
        )).data.toDomain()
    }

    override suspend fun renameSession(sessionId: String, title: String): SessionSummary = sessionCall(sessionId) {
        val client = requireActiveClient()
        client.updateSession(sessionId, ApiUpdateSessionRequest(title = title))
        client.getSession(sessionId).data.toDomain()
    }

    override suspend fun deleteSession(sessionId: String) {
        sessionCall(sessionId) { requireActiveClient().deleteSession(sessionId) }
    }

    override suspend fun forkSession(sessionId: String): SessionSummary =
        sessionCall(sessionId) { requireActiveClient().forkSession(sessionId).data.toDomain() }

    override suspend fun transcript(sessionId: String): List<TranscriptMessage> {
        val client = requireActiveClient()
        val result = mutableListOf<ApiMessage>()
        val seen = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val page = client.listMessages(sessionId, cursor = cursor)
            result.addAll(page.data)
            cursor = page.cursor.next
            check(cursor == null || seen.add(cursor)) { "Repeated message pagination cursor" }
        } while (cursor != null)
        return result.distinctBy { it.id }.sortedBy { it.time.created }.mapNotNull { it.toDomain(sessionId) }
    }

    override suspend fun sendPrompt(sessionId: String, prompt: ChatPrompt) {
        val client = requireActiveClient()
        prompt.agent?.let { client.switchSessionAgent(sessionId, ApiSwitchSessionAgentRequest(it)) }
        prompt.model?.let {
            client.switchSessionModel(sessionId, ApiSwitchSessionModelRequest(ApiModelRef(id = it.modelId, providerID = it.providerId)))
        }
        client.sendPrompt(sessionId, ApiPromptRequest(text = prompt.text))
    }

    override fun decode(type: String, payload: String): ChatEvent? = chatDecoder.decode(type, payload)

    private fun requireActiveClient(): OpenCodeApiClient = activeClient ?: throw InteractionNotConnectedException()

    internal fun isCredentialPermitActive(): Boolean = authorizationsEnabled && credentialPermit != null

    public fun negotiatesJsonContent(): Boolean = httpClient.pluginOrNull(ContentNegotiation) != null

    public fun permissionGateway(baseUrl: String): OpenCodePermissionGateway = OpenCodePermissionGateway(
        httpClient = httpClient, baseUrl = baseUrl, authTokenProvider = { currentCredential() },
    )

    public fun eventTransport(baseUrl: String): EventTransport = NetworkingRealtimeTransport(
        httpClient = httpClient, baseUrl = baseUrl, authTokenProvider = { currentCredential() },
    )
}
