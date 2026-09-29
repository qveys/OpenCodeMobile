package org.opencodemobile.shared.testsupport

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineConfig
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
import io.ktor.util.InternalAPI
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.toByteArray
import io.ktor.utils.io.writeStringUtf8
import io.ktor.utils.io.writer
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
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
) {
    public val baseUrl: String = BASE_URL

    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val createdSessions: MutableList<MockSession> = mutableListOf()
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
            expectSuccess = false
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
            handle(data, currentCoroutineContext())
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

            method == HttpMethod.Get && path == SESSION_PATH ->
                jsonResponse(sessionsJson(), HttpStatusCode.OK, callContext)

            method == HttpMethod.Get && path == SESSION_STATUS_PATH ->
                jsonResponse("{}", HttpStatusCode.OK, callContext)

            method == HttpMethod.Post && path == SESSION_PATH -> {
                val created = nextSession()
                jsonResponse(OpenCodeFixtures.sessionJson(created), HttpStatusCode.Created, callContext)
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
                questionReplyLog += MockQuestionReply(requestID, decision = "reply")
                jsonResponse("true", HttpStatusCode.OK, callContext)
            }

            method == HttpMethod.Post && path.startsWith("$QUESTION_PATH/") && path.endsWith("/reject") -> {
                val requestID = path.removePrefix("$QUESTION_PATH/").removeSuffix("/reject")
                questionReplyLog += MockQuestionReply(requestID, decision = "reject")
                jsonResponse("true", HttpStatusCode.OK, callContext)
            }

            else -> notFoundJson(callContext)
        }
    }

    private fun allSessions(): List<MockSession> = OpenCodeFixtures.sessions + createdSessions

    private fun sessionsJson(): String = OpenCodeFixtures.sessionsJson(allSessions())

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

    private fun nextSession(): MockSession {
        sessionSequence += 1
        val sequence = sessionSequence
        val session = MockSession(
            id = "ses_mock_${1000 + sequence}",
            title = "Created mock session $sequence",
            directory = OpenCodeFixtures.DIRECTORY,
            created = OpenCodeFixtures.BASE_TIME + sequence,
            updated = OpenCodeFixtures.BASE_TIME + sequence,
        )
        createdSessions += session
        return session
    }

    private fun isUnauthorized(request: HttpRequestData): Boolean {
        val token = requiredBearerToken ?: return false
        return request.headers[HttpHeaders.Authorization] != "Bearer $token"
    }

    /**
     * Serves `GET /event`.
     *
     * The scripted event list advances monotonically across connections ([eventCursor]) so a
     * reconnecting client receives only events that occur after it reconnects — already
     * delivered events are never replayed. [MockOpenCodeScenario.Disconnect] and
     * [MockOpenCodeScenario.Reconnect] truncate the **first** connection after
     * [MockOpenCodeStreamConfig.disconnectAfterEvents] events and end the body there, which is
     * how the client observes a dropped SSE connection; the next connection resumes the script.
     * A flush is issued after every event so the body is readable progressively.
     */
    private fun eventStreamResponse(callContext: CoroutineContext): HttpResponseData {
        val script = scriptFor(scenario)
        val start = eventCursor
        val remaining = script.drop(start)
        val truncating = scenario == MockOpenCodeScenario.Disconnect || scenario == MockOpenCodeScenario.Reconnect
        val take = if (truncating && eventStreamConnectionCount == 0) {
            minOf(streamConfig.disconnectAfterEvents, remaining.size)
        } else {
            remaining.size
        }
        val slice = remaining.take(take)
        eventCursor = start + slice.size
        eventStreamConnectionCount += 1

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

    public companion object {
        public const val BASE_URL: String = "http://mock.opencode.test"
        public const val HEALTH_PATH: String = "/global/health"
        public const val SESSION_PATH: String = "/session"
        public const val SESSION_STATUS_PATH: String = "/session/status"
        public const val EVENT_PATH: String = "/event"
        public const val PERMISSION_PATH: String = "/permission"
        public const val QUESTION_PATH: String = "/question"

        private const val ERROR_JSON: String =
            """{"error":{"type":"server_error","message":"scripted mock server failure"}}"""
        private const val UNAUTHORIZED_JSON: String =
            """{"error":{"type":"unauthorized","message":"missing or invalid credentials"}}"""
    }
}
