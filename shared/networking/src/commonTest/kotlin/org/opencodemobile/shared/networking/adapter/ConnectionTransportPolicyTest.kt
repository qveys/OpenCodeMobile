package org.opencodemobile.shared.networking.adapter

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.request
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

class ConnectionTransportPolicyTest {

    @Test
    fun aRedirectIsNotFollowed() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content = "",
                status = HttpStatusCode.Found,
                headers = headersOf(HttpHeaders.Location, "http://evil.example/next"),
            )
        }
        val client = HttpClient(engine) { applyConnectionTransportPolicy() }

        val response = client.get("https://self.example/x")

        assertEquals(HttpStatusCode.Found, response.status)
        assertEquals(1, engine.requestHistory.size, "a 3xx must not trigger a second request")
    }

    @Test
    fun theTransportPolicyAlsoInstallsTheMethodAllowlist() = runTest {
        val engine = MockEngine { _ -> respond("", HttpStatusCode.OK) }
        val client = HttpClient(engine) { applyConnectionTransportPolicy() }

        assertFailsWith<ConnectionPolicyException.MethodNotAllowed> {
            client.request("https://self.example/x") { method = HttpMethod.Put }
        }
        assertTrue(engine.requestHistory.isEmpty(), "a disallowed method must not reach the engine")
    }
}
