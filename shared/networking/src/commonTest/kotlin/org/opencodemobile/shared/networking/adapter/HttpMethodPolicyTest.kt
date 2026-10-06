package org.opencodemobile.shared.networking.adapter

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.request
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.opencodemobile.shared.domain.connection.ConnectionPolicyException

class HttpMethodPolicyTest {

    @Test
    fun anAllowedMethodReachesTheEngine() = runTest {
        val engine = recordingEngine()
        val client = HttpClient(engine) { installHttpMethodPolicy() }

        client.request("https://example.test/x") { method = HttpMethod.Get }

        assertEquals(1, engine.requestHistory.size)
        assertEquals(HttpMethod.Get, engine.requestHistory.first().method)
    }

    @Test
    fun aDisallowedMethodIsRefusedBeforeItReachesTheEngine() = runTest {
        val engine = recordingEngine()
        val client = HttpClient(engine) { installHttpMethodPolicy() }

        val failure = assertFailsWith<ConnectionPolicyException.MethodNotAllowed> {
            client.request("https://example.test/x") { method = HttpMethod.Put }
        }

        assertEquals("PUT", failure.method)
        assertTrue(engine.requestHistory.isEmpty(), "a disallowed method must not reach the engine")
    }

    private fun recordingEngine(): MockEngine = MockEngine { _ ->
        respond(
            content = "{}",
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )
    }
}
