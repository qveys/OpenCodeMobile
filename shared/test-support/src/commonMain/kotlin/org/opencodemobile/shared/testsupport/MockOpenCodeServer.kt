package org.opencodemobile.shared.testsupport

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * A minimal, deterministic, in-process fake of the OpenCode Server v2 HTTP surface.
 *
 * It is backed by Ktor's [MockEngine], so no socket is ever opened and every response is
 * produced from the pinned [OpenCodeFixtures]. This is the baseline required by the
 * Cahier des charges v1.0 §10.2 and by OPE-32; it covers the health handshake, the session
 * list and a scripted SSE stream, plus the failure scenarios (unsupported version,
 * auth failure, malformed event, server error) needed by the connection and realtime tests
 * in L1/L2.
 *
 * Usage:
 * ```kotlin
 * val server = MockOpenCodeServer().start()
 * try {
 *     // `server.client` already has ContentNegotiation installed:
 *     // val api = OpenCodeApiClient(server.baseUrl, server.client)  // from shared/networking
 *     val health = server.client.get("${server.baseUrl}/global/health").bodyAsText()
 * } finally {
 *     server.stop()
 * }
 * ```
 *
 * Generated OpenCode API types must not be imported outside `shared/networking` (Rule R3),
 * so this module only exposes a plain [HttpClient] plus the endpoint constants; callers in
 * `shared/networking` wire it to `OpenCodeV2Adapter` / the generated client themselves.
 */
public class MockOpenCodeServer(
    public val scenario: MockOpenCodeScenario = MockOpenCodeScenario.Default,
    private val requiredBearerToken: String? = null,
) {
    public val baseUrl: String = BASE_URL

    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val createdSessions: MutableList<MockSession> = mutableListOf()
    private val requestLog: MutableList<String> = mutableListOf()
    private var sessionSequence: Int = 0
    private var httpClient: HttpClient? = null

    /** True between a successful [start] and the matching [stop]. */
    public val isRunning: Boolean
        get() = httpClient != null

    /** Every request line (`"GET /session"`) seen since the last [start], for assertions. */
    public val requests: List<String>
        get() = requestLog.toList()

    /** Starts the server. Idempotent: calling it twice keeps the same client. */
    public fun start(): MockOpenCodeServer {
        if (httpClient != null) return this
        val engine = MockEngine { request -> handle(request) }
        httpClient = HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(json) }
        }
        return this
    }

    /** Stops the server, closes the client and clears all mutable state. Idempotent. */
    public fun stop() {
        httpClient?.close()
        httpClient = null
        createdSessions.clear()
        requestLog.clear()
        sessionSequence = 0
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

    private fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
        val method = request.method
        val path = request.url.encodedPath
        requestLog += "${method.value} $path"

        if (scenario == MockOpenCodeScenario.ServerError) {
            return respondJson(ERROR_JSON, HttpStatusCode.InternalServerError)
        }
        if (scenario == MockOpenCodeScenario.AuthenticationFailure || isUnauthorized(request)) {
            return respondJson(UNAUTHORIZED_JSON, HttpStatusCode.Unauthorized)
        }

        return when {
            method == HttpMethod.Get && path == HEALTH_PATH -> {
                val version = if (scenario == MockOpenCodeScenario.UnsupportedVersion) {
                    OpenCodeFixtures.UNSUPPORTED_SERVER_VERSION
                } else {
                    OpenCodeFixtures.SERVER_VERSION
                }
                respondJson(OpenCodeFixtures.healthJson(version))
            }

            method == HttpMethod.Get && path == SESSION_PATH -> respondJson(sessionsJson())

            method == HttpMethod.Get && path == SESSION_STATUS_PATH -> respondJson("{}")

            method == HttpMethod.Post && path == SESSION_PATH -> {
                val created = nextSession()
                respondJson(OpenCodeFixtures.sessionJson(created), HttpStatusCode.Created)
            }

            method == HttpMethod.Get && path.startsWith("$SESSION_PATH/") -> {
                val id = path.removePrefix("$SESSION_PATH/")
                val session = allSessions().firstOrNull { it.id == id }
                if (session != null) respondJson(OpenCodeFixtures.sessionJson(session)) else notFoundJson()
            }

            method == HttpMethod.Post && path.endsWith("/abort") -> respondJson("""{"aborted":true}""")

            method == HttpMethod.Get && path == EVENT_PATH -> respondEventStream()

            else -> notFoundJson()
        }
    }

    private fun allSessions(): List<MockSession> = OpenCodeFixtures.sessions + createdSessions

    private fun sessionsJson(): String = OpenCodeFixtures.sessionsJson(allSessions())

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

    private fun MockRequestHandleScope.respondEventStream(): HttpResponseData {
        val events = when (scenario) {
            MockOpenCodeScenario.MalformedEvent -> OpenCodeFixtures.malformedSseEvents()
            else -> OpenCodeFixtures.defaultSseEvents()
        }
        return respond(
            content = MockSseCodec.encode(events),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
        )
    }

    private fun MockRequestHandleScope.respondJson(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): HttpResponseData = respond(
        content = body,
        status = status,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

    private fun MockRequestHandleScope.notFoundJson(): HttpResponseData =
        respondJson("""{"error":{"type":"not_found","message":"no mock route for this request"}}""", HttpStatusCode.NotFound)

    public companion object {
        public const val BASE_URL: String = "http://mock.opencode.test"
        public const val HEALTH_PATH: String = "/global/health"
        public const val SESSION_PATH: String = "/session"
        public const val SESSION_STATUS_PATH: String = "/session/status"
        public const val EVENT_PATH: String = "/event"

        private const val ERROR_JSON: String =
            """{"error":{"type":"server_error","message":"scripted mock server failure"}}"""
        private const val UNAUTHORIZED_JSON: String =
            """{"error":{"type":"unauthorized","message":"missing or invalid credentials"}}"""
    }
}