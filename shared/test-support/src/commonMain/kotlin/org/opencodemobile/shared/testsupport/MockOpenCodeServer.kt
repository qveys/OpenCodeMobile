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
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.toByteArray
import io.ktor.utils.io.writeStringUtf8
import io.ktor.utils.io.writer
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A decision sent to `POST /permission/{requestID}/reply`, captured for assertions. */
public data class MockPermissionReply(
    public val requestID: String,
    public val reply: String?,
    public val message: String? = null,
)

/** A decision sent to `POST /question/{requestID}/reply` or `/reject`, captured for assertions. */
public data class MockQuestionReply(
    public val requestID: String,
    public val decision: String,
    /** The `answers` array parsed off the reply body, when the request was a reply. */
    public val answers: List<List<String>>? = null,
)

/**
 * A deterministic, in-process fake of the OpenCode Server v2 HTTP surface.
 *
 * It is backed by a small custom Ktor [io.ktor.client.engine.HttpClientEngine] — not
 * `MockEngine` — so that `GET /event` can write its body **incrementally** into a
 * [io.ktor.utils.io.ByteWriteChannel]: one event at a time, with a flush after each event,
 * an optional inter-event delay, and a configurable early end of the body. This is what
 * makes the `streaming`, `slow-network`, `disconnect`, `reconnect` and
 * `permission-request` scenarios expressible; `MockEngine` returned a single concatenated
 * body with no point of observation or interruption while the body was read.
 *
 * Request/response routes (health, sessions, permissions, questions, the failure
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
    private val questionReplyLog: MutableList<MockQuestionReply> = mutableListOf()
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

    /** Decisions received on `POST /permission/{requestID}/reply`, in arrival order. */
    public val permissionReplies: List<MockPermissionReply>
        get() = permissionReplyLog.toList()

    /** Decisions received on the question reply/reject routes, in arrival order. */
    public val questionReplies: List<MockQuestionReply>
        get() = questionReplyLog.toList()

    /** Number of `GET /event` connections served since the last [start]. */
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
        questionReplyLog.clear()
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
     * streams `/event` bodies through a [io.ktor.utils.io.ByteWriteChannel].
     */
    private inner class Engine : HttpClientEngineBase("mock-opencode") {
        override val config: HttpClientEngineConfig = HttpClientEngineConfig()

        @OptIn(InternalAPI::class)
        override suspend fun execute(data: HttpRequestData): HttpResponseData =
            handle(data, callContext())
    }

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
            method == HttpMethod.Get && path == HEALTH_PATH -> {
                val version = if (scenario == MockOpenCodeScenario.UnsupportedVersion) {
                    OpenCodeFixtures.UNSUPPORTED_SERVER_VERSION
                } else {
                    OpenCodeFixtures.SERVER_VERSION
                }
                jsonResponse(OpenCodeFixtures.healthJson(version), HttpStatusCode.OK, callContext)
            }

            // The server's own published surface: capability detection reads this
            // instead of assuming a feature set (V1-04).
            method == HttpMethod.Get && path == DOC_PATH ->
                jsonResponse(
                    OpenCodeFixtures.serverDocumentJson(
                        forkAvailable = scenario != MockOpenCodeScenario.NoFork,
                    ),
                    HttpStatusCode.OK,
                    callContext,
                )

            method == HttpMethod.Get && path == SESSION_PATH ->
                jsonResponse(sessionsJson(), HttpStatusCode.OK, callContext)

            method == HttpMethod.Get && path == SESSION_STATUS_PATH ->
                jsonResponse(sessionStatusJson(), HttpStatusCode.OK, callContext)

            method == HttpMethod.Post && path == SESSION_PATH -> {
                val body = readBodyText(request.body)
                if (bodyContainsKey(body, "directory")) {
                    // The pinned spec's POST /session body is additionalProperties:false
                    // and has no `directory`; it is a query parameter. Reject it here so a
                    // regression in the create path is caught by the harness.
                    badRequestJson(callContext)
                } else {
                    val created = nextSession(
                        title = parseTitle(body),
                        // `directory` is a query parameter on POST /session in the
                        // pinned spec; the mock reads it there, like a real server.
                        directory = request.url.parameters["directory"],
                    )
                    jsonResponse(OpenCodeFixtures.sessionJson(created), HttpStatusCode.Created, callContext)
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
                        jsonResponse(OpenCodeFixtures.sessionJson(forked), HttpStatusCode.Created, callContext)
                    }
                }
            }

            method == HttpMethod.Patch && path.startsWith("$SESSION_PATH/") -> {
                val id = path.removePrefix("$SESSION_PATH/")
                val existing = allSessions().firstOrNull { it.id == id }
                val title = parseTitle(readBodyText(request.body))
                when {
                    existing == null -> notFoundJson(callContext)
                    title == null -> badRequestJson(callContext)
                    else -> {
                        val renamed = existing.copy(title = title, updated = existing.updated + 1L)
                        sessionOverrides[id] = renamed
                        jsonResponse(OpenCodeFixtures.sessionJson(renamed), HttpStatusCode.OK, callContext)
                    }
                }
            }

            method == HttpMethod.Delete && path.startsWith("$SESSION_PATH/") -> {
                val id = path.removePrefix("$SESSION_PATH/")
                deletedSessionIds += id
                jsonResponse("true", HttpStatusCode.OK, callContext)
            }

            method == HttpMethod.Get && path.startsWith("$SESSION_PATH/") -> {
                val id = path.removePrefix("$SESSION_PATH/")
                val session = allSessions().firstOrNull { it.id == id }
                if (session != null) {
                    jsonResponse(OpenCodeFixtures.sessionJson(session), HttpStatusCode.OK, callContext)
                } else {
                    notFoundJson(callContext)
                }
            }

            method == HttpMethod.Post && path.endsWith("/abort") ->
                jsonResponse("""{"aborted":true}""", HttpStatusCode.OK, callContext)

            method == HttpMethod.Get && path == EVENT_PATH -> eventStreamResponse(callContext)

            method == HttpMethod.Get && path == PERMISSION_PATH ->
                jsonResponse(permissionsJson(), HttpStatusCode.OK, callContext)

            method == HttpMethod.Post && path.startsWith("$PERMISSION_PATH/") && path.endsWith("/reply") -> {
                val requestID = path.removePrefix("$PERMISSION_PATH/").removeSuffix("/reply")
                val decision = parsePermissionReply(readBodyText(request.body))
                permissionReplyLog += MockPermissionReply(
                    requestID = requestID,
                    reply = decision?.first,
                    message = decision?.second,
                )
                jsonResponse("true", HttpStatusCode.OK, callContext)
            }

            method == HttpMethod.Get && path == QUESTION_PATH ->
                jsonResponse(questionsJson(), HttpStatusCode.OK, callContext)

            method == HttpMethod.Post && path.startsWith("$QUESTION_PATH/") && path.endsWith("/reply") -> {
                val requestID = path.removePrefix("$QUESTION_PATH/").removeSuffix("/reply")
                questionReplyLog += MockQuestionReply(
                    requestID = requestID,
                    decision = "reply",
                    answers = parseQuestionAnswers(readBodyText(request.body)),
                )
                jsonResponse("true", HttpStatusCode.OK, callContext)
            }

            method == HttpMethod.Post && path.startsWith("$QUESTION_PATH/") && path.endsWith("/reject") -> {
                val requestID = path.removePrefix("$QUESTION_PATH/").removeSuffix("/reject")
                questionReplyLog += MockQuestionReply(requestID, decision = "reject")
                jsonResponse("true", HttpStatusCode.OK, callContext)
            }

            // --- V1-09: server-exposed models and agents ---

            method == HttpMethod.Get && path == PROVIDER_PATH ->
                jsonResponse(catalogProvidersJson(), HttpStatusCode.OK, callContext)

            method == HttpMethod.Get && path == AGENT_PATH ->
                jsonResponse(catalogAgentsJson(), HttpStatusCode.OK, callContext)

            else -> notFoundJson(callContext)
        }
    }

    private fun allSessions(): List<MockSession> =
        (OpenCodeFixtures.sessions + createdSessions)
            .map { session -> sessionOverrides[session.id] ?: session }
            .filterNot { session -> session.id in deletedSessionIds }

    private fun sessionsJson(): String = OpenCodeFixtures.sessionsJson(allSessions())

    private fun sessionStatusJson(): String = OpenCodeFixtures.sessionStatusJson(allSessions())

    private fun permissionsJson(): String =
        if (scenario == MockOpenCodeScenario.PermissionRequest) {
            OpenCodeFixtures.permissionsJson()
        } else {
            "[]"
        }

    private fun questionsJson(): String =
        if (scenario == MockOpenCodeScenario.PermissionRequest) {
            OpenCodeFixtures.questionsJson()
        } else {
            "[]"
        }

    private fun catalogProvidersJson(): String =
        if (scenario == MockOpenCodeScenario.NoCatalog) {
            OpenCodeFixtures.emptyProvidersJson()
        } else {
            OpenCodeFixtures.providersJson()
        }

    private fun catalogAgentsJson(): String =
        if (scenario == MockOpenCodeScenario.NoCatalog) {
            "[]"
        } else {
            OpenCodeFixtures.agentsJson()
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
     * A session created by `POST /session/{id}/fork`: a new id, the parent id
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
     * Serves `GET /event`.
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
     * [MockOpenCodeStreamConfig.disconnectConnections] > 1 models a server that stays down for
     * several attempts; [MockOpenCodeStreamConfig.replayFromEventId] re-delivers already
     * consumed ids so dedup by server id can be asserted; [MockOpenCodeStreamConfig.tailEvents]
     * (with `tailDelayMillis` / `keepOpenMillis`) emits activity later on a connection that
     * stays open ("resume live") instead of ending the body as soon as the script is empty.
     */
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
        val replayed = replayPrefix(script, start)
        val slice = replayed + fresh
        eventCursor = start + fresh.size
        eventStreamConnectionCount += 1

        // "Resume live": only the connection that actually exhausts the script stays open and
        // may emit later activity. A truncated connection never does.
        val exhaustsNow = !truncating && start < script.size && eventCursor >= script.size
        val tails = if (exhaustsNow) streamConfig.tailEvents else emptyList()

        val delayMillis = when (scenario) {
            MockOpenCodeScenario.Streaming -> streamConfig.streamingDelayMillis
            MockOpenCodeScenario.SlowNetwork -> streamConfig.slowNetworkDelayMillis
            else -> 0L
        }

        val writerJob = CoroutineScope(callContext).writer(autoFlush = true) {
            slice.forEachIndexed { index, event ->
                if (index > 0 && delayMillis > 0L) delay(delayMillis)
                channel.writeStringUtf8(event.encode())
                channel.flush()
            }
            if (tails.isNotEmpty()) {
                if (streamConfig.tailDelayMillis > 0L) delay(streamConfig.tailDelayMillis)
                tails.forEach { event ->
                    channel.writeStringUtf8(event.encode())
                    channel.flush()
                }
            }
            if (streamConfig.keepOpenMillis > 0L) delay(streamConfig.keepOpenMillis)
        }

        return HttpResponseData(
            statusCode = HttpStatusCode.OK,
            requestTime = GMTDate(),
            headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
            version = HttpProtocolVersion.HTTP_1_1,
            body = writerJob.channel,
            callContext = callContext,
        )
    }

    private fun MockOpenCodeScenario.isDisconnecting(): Boolean =
        this == MockOpenCodeScenario.Disconnect || this == MockOpenCodeScenario.Reconnect

    /**
     * The already-consumed tail of the script that
     * [MockOpenCodeStreamConfig.replayFromEventId] asks the server to replay on resubscribe.
     * Empty unless the configured id was delivered before the current cursor.
     */
    private fun replayPrefix(script: List<MockSseEvent>, cursor: Int): List<MockSseEvent> {
        val fromId = streamConfig.replayFromEventId ?: return emptyList()
        if (cursor <= 0) return emptyList()
        val from = script.indexOfFirst { it.id == fromId }
        if (from < 0 || from >= cursor) return emptyList()
        return script.subList(from, cursor)
    }

    private fun scriptFor(scenario: MockOpenCodeScenario): List<MockSseEvent> = when (scenario) {
        MockOpenCodeScenario.MalformedEvent -> OpenCodeFixtures.malformedSseEvents()
        MockOpenCodeScenario.Streaming -> OpenCodeFixtures.streamingSseEvents()
        MockOpenCodeScenario.Disconnect -> OpenCodeFixtures.disconnectSseEvents()
        MockOpenCodeScenario.Reconnect -> OpenCodeFixtures.disconnectSseEvents()
        MockOpenCodeScenario.SlowNetwork -> OpenCodeFixtures.slowNetworkSseEvents()
        MockOpenCodeScenario.LongTranscript ->
            OpenCodeFixtures.longTranscriptSseEvents(streamConfig.longTranscriptPartCount)
        MockOpenCodeScenario.PermissionRequest -> OpenCodeFixtures.permissionSseEvents()
        else -> OpenCodeFixtures.defaultSseEvents()
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
        val reply = (obj["reply"] as? JsonPrimitive)?.content ?: return null
        val message = (obj["message"] as? JsonPrimitive)?.content
        return reply to message
    }

    /** Parses `{ "title": "..." }` off a `PATCH /session/{id}` body, or null. */
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

    /** Parses `{ "answers": [[label, ...], ...] }`, or null when the body is not a reply. */
    private fun parseQuestionAnswers(text: String?): List<List<String>>? {
        if (text.isNullOrBlank()) return null
        val obj = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        val answers = obj["answers"] as? JsonArray ?: return null
        return answers.map { question ->
            (question as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
        }
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
        public const val HEALTH_PATH: String = "/global/health"
        public const val DOC_PATH: String = "/doc"
        public const val SESSION_PATH: String = "/session"
        public const val SESSION_STATUS_PATH: String = "/session/status"
        public const val EVENT_PATH: String = "/event"
        public const val PERMISSION_PATH: String = "/permission"
        public const val QUESTION_PATH: String = "/question"
        public const val PROVIDER_PATH: String = "/provider"
        public const val AGENT_PATH: String = "/agent"

        private const val ERROR_JSON: String =
            """{"error":{"type":"server_error","message":"scripted mock server failure"}}"""
        private const val UNAUTHORIZED_JSON: String =
            """{"error":{"type":"unauthorized","message":"missing or invalid credentials"}}"""
    }
}
