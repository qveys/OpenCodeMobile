package org.opencodemobile.shared.testsupport

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.callContext
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.toByteArray
import io.ktor.utils.io.writeStringUtf8
import io.ktor.utils.io.writer
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** A decision sent to `POST /api/session/{sessionID}/permission/{requestID}/reply`, captured for assertions. */
public data class MockPermissionReply(
    public val requestID: String,
    public val reply: String?,
    public val message: String? = null,
    public val sessionId: String,
)

/** A session-scoped form answer or cancellation captured for assertions. */
public data class MockFormReply(
    public val sessionId: String,
    public val requestID: String,
    public val decision: String,
    public val answer: JsonObject? = null,
)

/**
 * A prompt received on `POST /api/session/{sessionID}/prompt`, captured for assertions.
 *
 * The count per session is the D9 proof: a reconnecting client must never send
 * the same prompt twice.
 */
public data class MockPrompt(
    public val sessionId: String,
    public val text: String,
)

/**
 * An abort received on `POST /api/session/{sessionID}/interrupt`, captured for assertions.
 *
 * [directory] is the optional `directory` query parameter (ADR-0002 §3.3); a
 * test asserts the adapter forwards the active project root.
 */
public data class MockAbort(
    public val sessionId: String,
    public val directory: String? = null,
)

/**
 * A deterministic, in-process fake of the OpenCode Server v2 HTTP surface.
 *
 * It is backed by a small custom Ktor [io.ktor.client.engine.HttpClientEngine] — not
 * `MockEngine` — so that `GET /api/event` can write its body **incrementally** into a
 * [io.ktor.utils.io.ByteWriteChannel]: one event at a time, with a flush after each event,
 * an optional inter-event delay, and a configurable early end of the body. This is what
 * makes the `streaming`, `slow-network`, `disconnect`, `reconnect` and
 * `permission-request` scenarios expressible; `MockEngine` returned a single concatenated
 * body with no point of observation or interruption while the body was read.
 *
 * Request/response routes (info, sessions, permissions, forms, the failure
 * scenarios) share the same engine so every fixture comes from one place.
 *
 * Usage:
 * ```kotlin
 * val server = MockOpenCodeServer(MockOpenCodeScenario.Streaming).start()
 * try {
 *     // `server.client` already has ContentNegotiation installed:
 *     val events = server.client.get("${server.baseUrl}${MockOpenCodeServer.EVENT_PATH}")
 *         .bodyAsChannel()
 * } finally {
 *     server.stop()
 * }
 * ```
 *
 * Generated OpenCode API types must not be imported outside `shared/networking` (Rule R3),
 * so this module only exposes a plain [HttpClient] plus the endpoint constants; callers in
 * `shared/networking` wire it to `OpenCodeV2Adapter` / the generated client themselves.
 * See `README.md` in this module for the full scenario matrix and the disconnect/reconnect
 * semantics.
 */
// The mock server intentionally exposes the whole scenario surface on one type;
// the function-count rule is waived rather than scattering the fixture surface.
@Suppress("TooManyFunctions")
public class MockOpenCodeServer(
    public val scenario: MockOpenCodeScenario = MockOpenCodeScenario.Default,
    private val requiredBearerToken: String? = null,
    public val streamConfig: MockOpenCodeStreamConfig = MockOpenCodeStreamConfig(),
    /**
     * When true, the bound client throws on non-2xx responses, matching the
     * app's real client (`OpenCodeHttpClient.create`). The default stays false
     * so the existing streaming/routing tests keep reading error bodies.
     */
    private val expectSuccess: Boolean = false,
) {
    public val baseUrl: String = BASE_URL

    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val createdSessions: MutableList<MockSession> = mutableListOf()
    private val sessionOverrides: MutableMap<String, MockSession> = mutableMapOf()
    private val deletedSessionIds: MutableSet<String> = mutableSetOf()
    private val requestLog: MutableList<String> = mutableListOf()
    private val permissionReplyLog: MutableList<MockPermissionReply> = mutableListOf()
    private val formReplyLog: MutableList<MockFormReply> = mutableListOf()
    private val promptLog: MutableList<MockPrompt> = mutableListOf()
    private val abortLog: MutableList<MockAbort> = mutableListOf()

    /**
     * Sessions whose active turn was aborted; their turn is never replayed.
     *
     * A `StateFlow` (not a plain set) because the abort is recorded on the abort
     * request's thread while the `/api/event` writer polls it on another, so the
     * update must cross threads with a visibility guarantee.
     */
    private val abortedSessionIds: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet())

    /**
     * Part ids whose `session.text.ended` was written **before** an abort, i.e.
     * the server committed them to the authoritative transcript. A part written
     * after the abort (the in-flight orphan) is deliberately not committed.
     * A `StateFlow` for the same cross-thread visibility reason.
     */
    private val committedPartIds: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet())
    private var sessionSequence: Int = 0
    private var eventCursor: Int = 0
    private var eventStreamConnectionCount: Int = 0
    private var httpClient: HttpClient? = null
    private var engineRef: Engine? = null

    /** True between a successful [start] and the matching [stop]. */
    public val isRunning: Boolean
        get() = httpClient != null

    /** Every request line (`"GET /session"`) seen since the last [start], for assertions. */
    public val requests: List<String>
        get() = requestLog.toList()

    /** Decisions received on `POST /api/session/{sessionID}/permission/{requestID}/reply`, in arrival order. */
    public val permissionReplies: List<MockPermissionReply>
        get() = permissionReplyLog.toList()

    /** Decisions received on the form reply/cancel routes, in arrival order. */
    public val formReplies: List<MockFormReply>
        get() = formReplyLog.toList()

    /**
     * Prompts received on `POST /api/session/{id}/prompt`, in arrival order.
     * The count is the D9 proof: a reconnect must not replay a prompt.
     */
    public val prompts: List<MockPrompt>
        get() = promptLog.toList()

    /** Aborts received on `POST /api/session/{id}/interrupt`, in arrival order, with their `directory`. */
    public val aborts: List<MockAbort>
        get() = abortLog.toList()

    /** Sessions whose turn was aborted; the mock never replays their aborted turn. */
    public val abortedSessions: Set<String>
        get() = abortedSessionIds.value

    /**
     * Part ids the mock has actually emitted on `/api/event` (`session.text.ended`)
     * and that are therefore part of the authoritative transcript. A part written
     * after an abort (the in-flight orphan) is never added.
     */
    public val deliveredParts: Set<String>
        get() = committedPartIds.value

    /** Number of `GET /api/event` connections served since the last [start]. */
    public val eventStreamConnections: Int
        get() = eventStreamConnectionCount

    /** Starts the server. Idempotent: calling it twice keeps the same client. */
    public fun start(): MockOpenCodeServer {
        if (httpClient != null) return this
        val engine = Engine()
        engineRef = engine
        httpClient = HttpClient(engine) {
            expectSuccess = this@MockOpenCodeServer.expectSuccess
            install(ContentNegotiation) { json(json) }
        }
        return this
    }

    /** Stops the server, closes the client and clears all mutable state. Idempotent. */
    public fun stop() {
        httpClient?.close()
        engineRef?.close()
        httpClient = null
        engineRef = null
        createdSessions.clear()
        sessionOverrides.clear()
        deletedSessionIds.clear()
        requestLog.clear()
        permissionReplyLog.clear()
        formReplyLog.clear()
        promptLog.clear()
        abortLog.clear()
        abortedSessionIds.value = emptySet()
        committedPartIds.value = emptySet()
        sessionSequence = 0
        eventCursor = 0
        eventStreamConnectionCount = 0
    }

    /** `close()` alias so a server can be used with `try/finally` or `use`-style helpers. */
    public fun close() {
        stop()
    }

    /**
     * The client bound to the mock engine. Fails fast when the server is not running so a
     * forgotten [start] surfaces as a clear test error instead of a real network call.
     */
    public val client: HttpClient
        get() = httpClient ?: error("MockOpenCodeServer is not running: call start() first.")

    /**
     * The custom engine replacing `MockEngine`. It runs on the caller's `HttpClient` and
     * streams `/api/event` bodies through a [io.ktor.utils.io.ByteWriteChannel].
     */
    private inner class Engine : HttpClientEngineBase("mock-opencode") {
        override val config: HttpClientEngineConfig = HttpClientEngineConfig()

        @OptIn(InternalAPI::class)
        override suspend fun execute(data: HttpRequestData): HttpResponseData =
            handle(data, callContext())
    }

    // One routing switch over the pinned endpoint surface. The length and
    // branching come from covering every endpoint explicitly, which the tests
    // depend on; splitting it into per-path handlers would not reduce real risk.
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private suspend fun handle(request: HttpRequestData, callContext: CoroutineContext): HttpResponseData {
        val method = request.method
        val path = request.url.encodedPath
        requestLog += "${method.value} $path"

        if (scenario == MockOpenCodeScenario.ServerError) {
            return jsonResponse(ERROR_JSON, HttpStatusCode.InternalServerError, callContext)
        }
        if (scenario == MockOpenCodeScenario.AuthenticationFailure || isUnauthorized(request)) {
            return jsonResponse(UNAUTHORIZED_JSON, HttpStatusCode.Unauthorized, callContext)
        }

        return when {
            method == HttpMethod.Get && path == INFO_PATH -> {
                val version = if (scenario == MockOpenCodeScenario.UnsupportedVersion) {
                    OpenCodeFixtures.UNSUPPORTED_SERVER_VERSION
                } else {
                    OpenCodeFixtures.SERVER_VERSION
                }
                jsonResponse(OpenCodeFixtures.infoJson(version), HttpStatusCode.OK, callContext)
            }

            method == HttpMethod.Get && path == SESSION_PATH ->
                jsonResponse(sessionPageJson(request), HttpStatusCode.OK, callContext)

            method == HttpMethod.Get && path == SESSION_STATUS_PATH ->
                jsonResponse(sessionStatusJson(), HttpStatusCode.OK, callContext)

            // --- V1-05: transcript + async prompt ---

            // Declared before the generic `GET /api/session/{id}` route: `/message` is
            // a sub-resource, not a session id.
            method == HttpMethod.Get && path.startsWith("$SESSION_PATH/") && path.endsWith("/message") -> {
                val id = path.removePrefix("$SESSION_PATH/").removeSuffix("/message")
                jsonResponse(transcriptJson(id), HttpStatusCode.OK, callContext)
            }

            // The async prompt is recorded and answered immediately; the reply is
            // streamed on `GET /api/event`, exactly like a real server.
            method == HttpMethod.Post && path.startsWith("$SESSION_PATH/") && path.endsWith("/prompt") -> {
                val id = path.removePrefix("$SESSION_PATH/").removeSuffix("/prompt")
                val text = parsePromptText(readBodyText(request.body))
                    ?: return badRequestJson(callContext)
                promptLog += MockPrompt(sessionId = id, text = text.orEmpty())
                jsonResponse(OpenCodeFixtures.envelope(buildJsonObject {
                    put("id", "msg_user_${promptLog.size.toString().padStart(4, '0')}")
                    put("sessionID", id); put("type", "user")
                    put("delivery", "queue")
                    put("time", buildJsonObject { put("created", OpenCodeFixtures.BASE_TIME) })
                    put("payload", buildJsonObject { put("text", text.orEmpty()) })
                }).toString(), HttpStatusCode.OK, callContext)
            }

            method == HttpMethod.Post && path == SESSION_PATH -> {
                val body = readBodyText(request.body)
                if (bodyContainsKey(body, "directory")) {
                    // v2 uses location.directory, never a top-level directory.
                    badRequestJson(callContext)
                } else {
                    val created = nextSession(
                        title = parseTitle(body),
                        directory = (body?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() } as? JsonObject)
                            ?.get("location")?.let { it as? JsonObject }
                            ?.get("directory")?.let { it as? JsonPrimitive }?.contentOrNull,
                    )
                    jsonResponse(OpenCodeFixtures.sessionJson(created), HttpStatusCode.OK, callContext)
                }
            }

            method == HttpMethod.Post && path.startsWith("$SESSION_PATH/") && path.endsWith("/fork") -> {
                if (scenario == MockOpenCodeScenario.NoFork) {
                    notFoundJson(callContext)
                } else {
                    val id = path.removePrefix("$SESSION_PATH/").removeSuffix("/fork")
                    val parent = allSessions().firstOrNull { it.id == id }
                    if (parent == null) {
                        notFoundJson(callContext)
                    } else {
                        val forked = nextForkedSession(parent)
                        jsonResponse(OpenCodeFixtures.sessionJson(forked), HttpStatusCode.OK, callContext)
                    }
                }
            }

            method == HttpMethod.Post && Regex("$SESSION_PATH/[^/]+/permission/[^/]+/reply").matches(path) -> {
                val segments = path.removePrefix("$SESSION_PATH/").split("/")
                val decision = parsePermissionReply(readBodyText(request.body))
                if (decision == null || decision.first !in setOf("once", "always", "reject")) {
                    badRequestJson(callContext)
                } else if (segments[0] != OpenCodeFixtures.sessions.first().id || segments[2] != OpenCodeFixtures.PERMISSION_REQUEST_ID) {
                    notFoundJson(callContext)
                } else {
                    permissionReplyLog += MockPermissionReply(segments[2], decision.first, decision.second, segments[0])
                    noContentResponse(callContext)
                }
            }

            (method == HttpMethod.Post && Regex("$SESSION_PATH/[^/]+/form/[^/]+/reply").matches(path)) ||
                (method == HttpMethod.Delete && Regex("$SESSION_PATH/[^/]+/form/[^/]+").matches(path)) -> {
                val segments = path.removePrefix("$SESSION_PATH/").split("/")
                val answer = if (method == HttpMethod.Post) parseFormAnswer(readBodyText(request.body)) else null
                when {
                    segments[0] != OpenCodeFixtures.sessions.first().id || segments[2] != OpenCodeFixtures.FORM_REQUEST_ID -> notFoundJson(callContext)
                    method == HttpMethod.Post && answer == null -> badRequestJson(callContext)
                    else -> {
                        formReplyLog += MockFormReply(segments[0], segments[2], if (method == HttpMethod.Post) "reply" else "cancel", answer)
                        noContentResponse(callContext)
                    }
                }
            }

            method == HttpMethod.Patch && Regex("$SESSION_PATH/[^/]+").matches(path) -> {
                val id = path.removePrefix("$SESSION_PATH/")
                val existing = allSessions().firstOrNull { it.id == id }
                val title = parseTitle(readBodyText(request.body))
                when {
                    existing == null -> notFoundJson(callContext)
                    title == null -> badRequestJson(callContext)
                    else -> {
                        val renamed = existing.copy(title = title, updated = existing.updated + 1L)
                        sessionOverrides[id] = renamed
                        noContentResponse(callContext)
                    }
                }
            }

            method == HttpMethod.Delete && Regex("$SESSION_PATH/[^/]+").matches(path) -> {
                val id = path.removePrefix("$SESSION_PATH/")
                deletedSessionIds += id
                noContentResponse(callContext)
            }

            method == HttpMethod.Get && Regex("$SESSION_PATH/[^/]+").matches(path) -> {
                val id = path.removePrefix("$SESSION_PATH/")
                val session = allSessions().firstOrNull { it.id == id }
                if (session != null) {
                    jsonResponse(OpenCodeFixtures.sessionJson(session), HttpStatusCode.OK, callContext)
                } else {
                    notFoundJson(callContext)
                }
            }

            // Abort the active turn (V1-08). The mock records the exact request,
            // including the optional `directory` query parameter (ADR-0002 §3.3),
            // and marks the session so its aborted turn is never replayed.
            method == HttpMethod.Post && path.startsWith("$SESSION_PATH/") && path.endsWith("/interrupt") -> {
                val id = path.removePrefix("$SESSION_PATH/").removeSuffix("/interrupt")
                abortLog += MockAbort(sessionId = id, directory = request.url.parameters["directory"])
                abortedSessionIds.value = abortedSessionIds.value + id
                jsonResponse("""{"interrupted":true}""", HttpStatusCode.OK, callContext)
            }

            method == HttpMethod.Get && path == EVENT_PATH -> eventStreamResponse(callContext)

            method == HttpMethod.Get && path == PERMISSION_PATH ->
                jsonResponse(permissionsJson(), HttpStatusCode.OK, callContext)

            method == HttpMethod.Get && path == FORM_PATH ->
                jsonResponse(formsJson(), HttpStatusCode.OK, callContext)

            // --- V1-09: server-exposed models and agents ---

            method == HttpMethod.Get && path == PROVIDER_PATH ->
                catalogProvidersResponse(callContext)

            method == HttpMethod.Get && path == MODEL_PATH ->
                when (scenario) {
                    MockOpenCodeScenario.NoCatalogRoutes -> notFoundJson(callContext)
                    MockOpenCodeScenario.CatalogUnavailable -> jsonResponse(ERROR_JSON, HttpStatusCode.ServiceUnavailable, callContext)
                    MockOpenCodeScenario.NoCatalog -> jsonResponse(OpenCodeFixtures.envelope(buildJsonArray {}, location = true).toString(), HttpStatusCode.OK, callContext)
                    else -> jsonResponse(OpenCodeFixtures.modelsJson(), HttpStatusCode.OK, callContext)
                }

            method == HttpMethod.Get && path == AGENT_PATH ->
                catalogAgentsResponse(callContext)

            else -> notFoundJson(callContext)
        }
    }

    private fun allSessions(): List<MockSession> =
        (OpenCodeFixtures.sessions + createdSessions)
            .map { session -> sessionOverrides[session.id] ?: session }
            .filterNot { session -> session.id in deletedSessionIds }

    /** Opaque mock cursors exercise the adapter's internal pagination loop. */
    private fun sessionPageJson(request: HttpRequestData): String {
        val directory = request.url.parameters["directory"]
        val sessions = allSessions().filter { directory == null || it.directory == directory }
        val limit = request.url.parameters["limit"]?.toIntOrNull()?.coerceAtLeast(1) ?: 50
        val offset = request.url.parameters["cursor"]?.removePrefix("mock_")?.toIntOrNull() ?: 0
        val page = sessions.drop(offset).take(limit)
        return buildJsonObject {
            put("data", buildJsonArray { page.forEach { add(OpenCodeFixtures.sessionObject(it)) } })
            put("cursor", buildJsonObject {
                put("previous", if (offset > 0) JsonPrimitive("mock_${(offset - limit).coerceAtLeast(0)}") else kotlinx.serialization.json.JsonNull)
                put("next", if (offset + page.size < sessions.size) JsonPrimitive("mock_${offset + page.size}") else kotlinx.serialization.json.JsonNull)
            })
        }.toString()
    }

    private fun sessionStatusJson(): String = OpenCodeFixtures.sessionStatusJson(allSessions())

    private fun permissionsJson(): String =
        if (scenario == MockOpenCodeScenario.PermissionRequest && permissionReplyLog.isEmpty()) {
            OpenCodeFixtures.permissionsJson()
        } else {
            OpenCodeFixtures.envelope(buildJsonArray {}, location = true).toString()
        }

    private fun formsJson(): String =
        if (scenario == MockOpenCodeScenario.PermissionRequest && formReplyLog.isEmpty()) {
            OpenCodeFixtures.formsJson()
        } else {
            OpenCodeFixtures.envelope(buildJsonArray {}, location = true).toString()
        }

    /**
     * `GET /provider` for the V1-09 scenarios. [MockOpenCodeScenario.NoCatalog]
     * answers an empty list; [MockOpenCodeScenario.NoCatalogRoutes] answers `404`
     * (the route does not exist); [MockOpenCodeScenario.CatalogUnavailable]
     * answers `503` (a transient failure). Everything else answers the pinned
     * fixture, so the catalog is always the server's, never a built-in list.
     */
    private fun catalogProvidersResponse(callContext: CoroutineContext): HttpResponseData =
        when (scenario) {
            MockOpenCodeScenario.NoCatalogRoutes -> notFoundJson(callContext)
            MockOpenCodeScenario.CatalogUnavailable ->
                jsonResponse(ERROR_JSON, HttpStatusCode.ServiceUnavailable, callContext)
            MockOpenCodeScenario.NoCatalog ->
                jsonResponse(OpenCodeFixtures.emptyProvidersJson(), HttpStatusCode.OK, callContext)
            else -> jsonResponse(OpenCodeFixtures.providersJson(), HttpStatusCode.OK, callContext)
        }

    /** `GET /agent`; same scenario matrix as [catalogProvidersResponse]. */
    private fun catalogAgentsResponse(callContext: CoroutineContext): HttpResponseData =
        when (scenario) {
            MockOpenCodeScenario.NoCatalogRoutes -> notFoundJson(callContext)
            MockOpenCodeScenario.CatalogUnavailable ->
                jsonResponse(ERROR_JSON, HttpStatusCode.ServiceUnavailable, callContext)
            MockOpenCodeScenario.NoCatalog -> jsonResponse(OpenCodeFixtures.envelope(buildJsonArray {}, location = true).toString(), HttpStatusCode.OK, callContext)
            else -> jsonResponse(OpenCodeFixtures.agentsJson(), HttpStatusCode.OK, callContext)
        }

    private fun nextSession(
        title: String? = null,
        directory: String? = null,
    ): MockSession {
        sessionSequence += 1
        val sequence = sessionSequence
        val session = MockSession(
            id = "ses_mock_${1000 + sequence}",
            title = title ?: "Created mock session $sequence",
            directory = directory ?: OpenCodeFixtures.DIRECTORY,
            created = OpenCodeFixtures.BASE_TIME + sequence,
            updated = OpenCodeFixtures.BASE_TIME + sequence,
        )
        createdSessions += session
        return session
    }

    /**
     * A session created by `POST /api/session/{id}/fork`: a new id, the parent id
     * carried in `parentID`, and a title derived from the parent so the list
     * shows the relationship.
     */
    private fun nextForkedSession(parent: MockSession): MockSession {
        sessionSequence += 1
        val sequence = sessionSequence
        val forked = MockSession(
            id = "ses_mock_${1000 + sequence}",
            title = "${parent.title} (fork)",
            directory = parent.directory,
            created = OpenCodeFixtures.BASE_TIME + sequence,
            updated = OpenCodeFixtures.BASE_TIME + sequence,
            parentId = parent.id,
        )
        createdSessions += forked
        return forked
    }

    private fun isUnauthorized(request: HttpRequestData): Boolean {
        val token = requiredBearerToken ?: return false
        return request.headers[HttpHeaders.Authorization] != "Bearer $token"
    }

    /**
     * Serves `GET /api/event`.
     *
     * The scripted event list advances monotonically across connections ([eventCursor]) so a
     * reconnecting client normally receives only events that occur after it reconnects.
     * [MockOpenCodeScenario.Disconnect] and [MockOpenCodeScenario.Reconnect] truncate the first
     * [MockOpenCodeStreamConfig.disconnectConnections] connections after
     * [MockOpenCodeStreamConfig.disconnectAfterEvents] events and end the body there, which is
     * how the client observes a dropped SSE connection; the following connection resumes the
     * script. A flush is issued after every event so the body is readable progressively.
     *
     * Three extra knobs make the harder lines of `docs/ARCHITECTURE.md` §3.2 expressible:
     * [MockOpenCodeStreamConfig.disconnectConnections] > 1 models repeated disconnects;
     * [MockOpenCodeStreamConfig.tailEvents]
     * (with `tailDelayMillis` / `keepOpenMillis`) emits activity later on a connection that
     * stays open ("resume live") instead of ending the body as soon as the script is empty.
     */
    @Suppress("CyclomaticComplexMethod")
    private fun eventStreamResponse(callContext: CoroutineContext): HttpResponseData {
        val script = scriptFor(scenario)
        val start = eventCursor
        val remaining = script.drop(start)
        val truncating = scenario.isDisconnecting() &&
            eventStreamConnectionCount < streamConfig.disconnectConnections
        val take = if (truncating) {
            minOf(streamConfig.disconnectAfterEvents, remaining.size)
        } else {
            remaining.size
        }
        val fresh = remaining.take(take)
        // V1-08: an aborted turn is never replayed. The cursor still advances past
        // its events (they were consumed), and any replay of them is suppressed.
        val slice = fresh.filterNot { isAbortedTurnEvent(it) }
        eventCursor = start + fresh.size
        eventStreamConnectionCount += 1

        // "Resume live": only the connection that actually exhausts the script stays open and
        // may emit later activity. A truncated connection never does.
        val exhaustsNow = !truncating && start < script.size && eventCursor >= script.size
        val tails = if (exhaustsNow) streamConfig.tailEvents else emptyList()

        val delayMillis = when (scenario) {
            MockOpenCodeScenario.Streaming -> streamConfig.streamingDelayMillis
            MockOpenCodeScenario.SlowNetwork -> streamConfig.slowNetworkDelayMillis
            // Abort is synchronized by the abort signal itself, not by a delay.
            MockOpenCodeScenario.Abort -> 0L
            else -> 0L
        }

        val connection = eventStreamConnectionCount
        val writerJob = CoroutineScope(callContext).writer(autoFlush = true) {
            channel.writeStringUtf8(OpenCodeFixtures.connectedEvent(connection).encode())
            channel.writeStringUtf8(": heartbeat\n\n")
            channel.flush()
            if (scenario == MockOpenCodeScenario.Abort) {
                writeAbortAware(channel, slice, abortedSessionId, delayMillis)
            } else {
                slice.forEachIndexed { index, event ->
                    if (index > 0 && delayMillis > 0L) delay(delayMillis)
                    channel.writeStringUtf8(event.encode())
                    channel.flush()
                }
            }
            if (tails.isNotEmpty()) {
                if (streamConfig.tailDelayMillis > 0L) delay(streamConfig.tailDelayMillis)
                tails.forEach { event ->
                    channel.writeStringUtf8(event.encode())
                    channel.flush()
                }
            }
            var keepOpen = streamConfig.keepOpenMillis
            while (keepOpen > 0L) {
                val pause = minOf(keepOpen, 15_000L)
                delay(pause)
                keepOpen -= pause
                if (pause == 15_000L) { channel.writeStringUtf8(": heartbeat\n\n"); channel.flush() }
            }
        }

        return HttpResponseData(
            statusCode = HttpStatusCode.OK,
            requestTime = GMTDate(),
            headers = headersOf(
                HttpHeaders.ContentType to listOf(ContentType.Text.EventStream.toString()),
                HttpHeaders.CacheControl to listOf("no-cache, no-transform"),
                "X-Accel-Buffering" to listOf("no"),
            ),
            version = HttpProtocolVersion.HTTP_1_1,
            body = writerJob.channel,
            callContext = callContext,
        )
    }

    private fun MockOpenCodeScenario.isDisconnecting(): Boolean =
        this == MockOpenCodeScenario.Disconnect || this == MockOpenCodeScenario.Reconnect

    /** The session whose turn the [MockOpenCodeScenario.Abort] fixture aborts. */
    private val abortedSessionId: String
        get() = OpenCodeFixtures.sessions.first().id

    /**
     * Writes the [MockOpenCodeScenario.Abort] stream.
     *
     * The turn is held open at [MockOpenCodeStreamConfig.abortHoldAfterEvents]
     * until `POST /api/session/{id}/interrupt` lands; the event already in flight is then
     * written (it crossed the abort and is deliberately **not** committed to the
     * authoritative transcript), and the stream stops. Nothing after it is
     * delivered, so a reconnecting client never replays the aborted turn.
     */
    private suspend fun writeAbortAware(
        channel: ByteWriteChannel,
        slice: List<MockSseEvent>,
        sessionID: String,
        delayMillis: Long,
    ) {
        slice.forEachIndexed { index, event ->
            if (index > 0 && delayMillis > 0L) delay(delayMillis)
            if (index == streamConfig.abortHoldAfterEvents) {
                // Suspend until the abort lands instead of polling a timer: this
                // is timing-independent (no virtual-clock race) and every caller
                // of this scenario aborts.
                abortedSessionIds.first { sessionID in it }
            }
            channel.writeStringUtf8(event.encode())
            channel.flush()
            if (sessionID in abortedSessionIds.value) {
                // The turn stopped: the event just written was in flight at the
                // abort, so it is not committed and no further event is emitted.
                return
            }
            commitPartEvent(event)
        }
    }

    /** True when [event] belongs to a session whose turn was aborted. */
    private fun isAbortedTurnEvent(event: MockSseEvent): Boolean {
        val sessionID = eventSessionId(event) ?: return false
        return sessionID in abortedSessionIds.value
    }

    /** The `data.sessionID` of [event], or null when the payload has none. */
    private fun eventSessionId(event: MockSseEvent): String? {
        val root = runCatching { json.parseToJsonElement(event.data) }.getOrNull() as? JsonObject
            ?: return null
        val properties = root["data"] as? JsonObject ?: return null
        return (properties["sessionID"] as? JsonPrimitive)?.contentOrNull
    }

    /** Records a written `session.text.ended` as committed to the server transcript. */
    private fun commitPartEvent(event: MockSseEvent) {
        if (event.type != "session.text.ended") return
        val root = runCatching { json.parseToJsonElement(event.data) }.getOrNull() as? JsonObject
            ?: return
        val properties = root["data"] as? JsonObject ?: return
        val messageId = (properties["assistantMessageID"] as? JsonPrimitive)?.contentOrNull ?: return
        val ordinal = (properties["ordinal"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: return
        committedPartIds.value = committedPartIds.value + messageId + "$messageId:text:$ordinal"
    }

    private fun scriptFor(scenario: MockOpenCodeScenario): List<MockSseEvent> = when (scenario) {
        MockOpenCodeScenario.MalformedEvent -> OpenCodeFixtures.malformedSseEvents()
        MockOpenCodeScenario.Streaming -> OpenCodeFixtures.streamingSseEvents()
        MockOpenCodeScenario.Disconnect -> OpenCodeFixtures.disconnectSseEvents()
        MockOpenCodeScenario.Reconnect -> OpenCodeFixtures.disconnectSseEvents()
        MockOpenCodeScenario.SlowNetwork -> OpenCodeFixtures.slowNetworkSseEvents()
        MockOpenCodeScenario.Abort -> OpenCodeFixtures.abortSseEvents()
        MockOpenCodeScenario.LongTranscript ->
            OpenCodeFixtures.longTranscriptSseEvents(streamConfig.longTranscriptPartCount)
        MockOpenCodeScenario.PermissionRequest -> OpenCodeFixtures.permissionSseEvents()
        else -> OpenCodeFixtures.defaultSseEvents()
    }

    /**
     * `GET /api/session/{id}/message`: the user prompts recorded on `prompt`,
     * followed by the assistant messages derived from the scenario's SSE script,
     * so the stream and the reconciling transcript cannot drift apart.
     */
    private fun transcriptJson(sessionId: String): String {
        val assistant = assistantMessages(sessionId)
        val users = promptLog.filter { it.sessionId == sessionId }
        return OpenCodeFixtures.envelope(buildJsonArray {
            users.forEachIndexed { index, prompt -> add(OpenCodeFixtures.userMessageObject(index + 1, prompt.text, sessionId)) }
            assistant.forEach { add(it) }
        }, cursor = true).toString()
    }

    /** Projects complete public text blocks into v2 assistant messages. */
    private fun assistantMessages(sessionId: String): List<JsonObject> = scriptFor(scenario)
        .filter { it.type == "session.text.ended" }
        .mapNotNull { event ->
            val root = runCatching { json.parseToJsonElement(event.data) }.getOrNull() as? JsonObject
            val data = root?.get("data") as? JsonObject ?: return@mapNotNull null
            if ((data["sessionID"] as? JsonPrimitive)?.contentOrNull != sessionId) return@mapNotNull null
            val messageId = (data["assistantMessageID"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            if (scenario == MockOpenCodeScenario.Abort && messageId !in committedPartIds.value) return@mapNotNull null
            val text = (data["text"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            OpenCodeFixtures.messageEnvelopeObject(messageId, "assistant", listOf(buildJsonObject {
                put("type", "text"); put("text", text)
            }), sessionId)
        }

    /** Reads the `text` field of a `prompt` body. */
    private fun parsePromptText(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val obj = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        return (obj["text"] as? JsonPrimitive)?.contentOrNull
    }

    private suspend fun readBodyText(content: OutgoingContent): String? = when (content) {
        is OutgoingContent.NoContent -> null
        is OutgoingContent.ContentWrapper -> readBodyText(content.delegate())
        is OutgoingContent.ByteArrayContent -> content.bytes().decodeToString()
        is OutgoingContent.ReadChannelContent -> content.readFrom().toByteArray().decodeToString()
        is OutgoingContent.WriteChannelContent -> {
            val channel = ByteChannel()
            content.writeTo(channel)
            channel.flushAndClose()
            channel.toByteArray().decodeToString()
        }
        else -> null
    }

    private fun parsePermissionReply(text: String?): Pair<String, String?>? {
        if (text.isNullOrBlank()) return null
        val obj = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        val reply = (obj["decision"] as? JsonPrimitive)?.content ?: return null
        val message = (obj["message"] as? JsonPrimitive)?.content
        return reply to message
    }

    /** Parses `{ "title": "..." }` off a `PATCH /api/session/{id}` body, or null. */
    private fun parseTitle(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val obj = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        return (obj["title"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
    }

    /** Whether a JSON request body carries [key] at the top level. */
    private fun bodyContainsKey(text: String?, key: String): Boolean {
        if (text.isNullOrBlank()) return false
        val obj = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return false
        return obj.containsKey(key)
    }

    private fun parseFormAnswer(text: String?): JsonObject? {
        val obj = text?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() } as? JsonObject
        return obj?.get("answer") as? JsonObject
    }

    private fun jsonResponse(
        body: String,
        status: HttpStatusCode,
        callContext: CoroutineContext,
    ): HttpResponseData = HttpResponseData(
        statusCode = status,
        requestTime = GMTDate(),
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        version = HttpProtocolVersion.HTTP_1_1,
        body = ByteReadChannel(body),
        callContext = callContext,
    )

    private fun noContentResponse(callContext: CoroutineContext): HttpResponseData = HttpResponseData(
        statusCode = HttpStatusCode.NoContent,
        requestTime = GMTDate(),
        headers = headersOf(),
        version = HttpProtocolVersion.HTTP_1_1,
        body = ByteReadChannel(ByteArray(0)),
        callContext = callContext,
    )

    private fun notFoundJson(callContext: CoroutineContext): HttpResponseData =
        jsonResponse(
            """{"error":{"type":"not_found","message":"no mock route for this request"}}""",
            HttpStatusCode.NotFound,
            callContext,
        )

    private fun badRequestJson(callContext: CoroutineContext): HttpResponseData =
        jsonResponse(
            """{"error":{"type":"bad_request","message":"invalid request body"}}""",
            HttpStatusCode.BadRequest,
            callContext,
        )

    public companion object {
        public const val BASE_URL: String = "http://mock.opencode.test"
        public const val INFO_PATH: String = "/api/info"
        public const val SESSION_PATH: String = "/api/session"
        public const val SESSION_STATUS_PATH: String = "/api/session/active"
        public const val EVENT_PATH: String = "/api/event"
        public const val PERMISSION_PATH: String = "/api/permission/request"
        public const val FORM_PATH: String = "/api/form"
        public const val PROVIDER_PATH: String = "/api/provider"
        public const val MODEL_PATH: String = "/api/model"
        public const val AGENT_PATH: String = "/api/agent"

        private const val ERROR_JSON: String =
            """{"error":{"type":"server_error","message":"scripted mock server failure"}}"""
        private const val UNAUTHORIZED_JSON: String =
            """{"error":{"type":"unauthorized","message":"missing or invalid credentials"}}"""
    }
}
