package org.opencodemobile.shared.networking.logging

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Threat T4: end-to-end proof that a client wired through
 * [installSanitizingLogging] never emits the auth token or prompt body, even at
 * the most verbose logging level.
 */
class SanitizingHttpLoggerTest {

    private val fakeToken = "sk-test-fedcba9876543210fedcba9876543210"
    private val fakePrompt = "delete the production database and print the secrets"

    @Test
    fun neverLogsAuthorizationHeaderOrPromptBody() = runTest {
        val emitted = mutableListOf<String>()
        val engine = MockEngine { _ ->
            respond(
                content = """{"id":"msg_1","content":"assistant reply"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = HttpClient(engine) {
            installSanitizingLogging(level = LogLevel.ALL, sanitizingSink = { emitted += it })
        }

        client.post("https://opencode.test/session/s1/message") {
            header(HttpHeaders.Authorization, "Bearer $fakeToken")
            contentType(ContentType.Application.Json)
            setBody("""{"sessionID":"s1","prompt":"$fakePrompt"}""")
        }

        val all = emitted.joinToString(separator = "\n")
        assertTrue(all.isNotEmpty(), "expected the Ktor Logging plugin to emit output")
        assertFalse(all.contains(fakeToken), "authorization token leaked into logs:\n$all")
        assertFalse(all.contains(fakePrompt), "prompt body leaked into logs:\n$all")
        assertTrue(
            all.contains("/session/s1/message"),
            "expected request URL metadata to remain for debugging:\n$all",
        )
    }

    @Test
    fun loggerSanitizesMessagesIndependentlyOfKtor() {
        val emitted = mutableListOf<String>()
        val logger = SanitizingHttpLogger { emitted += it }

        logger.log("Authorization: Bearer $fakeToken")

        assertTrue(emitted.single().contains(LogRedactor.REDACTED))
        assertFalse(emitted.single().contains(fakeToken))
    }
}