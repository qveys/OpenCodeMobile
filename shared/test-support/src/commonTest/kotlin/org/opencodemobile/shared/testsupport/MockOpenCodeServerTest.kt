package org.opencodemobile.shared.testsupport

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end example that proves the [MockOpenCodeServer] harness works.
 *
 * The test drives the mock through a real Ktor client stack (request routing, headers and
 * body decoding all run) but no socket is opened: the engine is in-process. It covers the
 * three scenarios required by OPE-32 — health, session list and a scripted SSE stream —
 * plus lifecycle and every [MockOpenCodeScenario] the baseline must replay:
 * `UnsupportedVersion`, `AuthenticationFailure`, `MalformedEvent` and `ServerError`.
 */
class MockOpenCodeServerTest {
    private val server = MockOpenCodeServer()

    @AfterTest
    fun tearDown() {
        server.stop()
    }

    @Test
    fun startIsIdempotentAndStopClosesTheServer() {
        assertFalse(server.isRunning)
        server.start().start()
        assertTrue(server.isRunning)
        server.stop()
        assertFalse(server.isRunning)
    }

    @Test
    fun healthCheckReturnsThePinnedFixture() = runTest {
        server.start()
        val body = server.client.get("${server.baseUrl}${MockOpenCodeServer.HEALTH_PATH}").bodyAsText()
        val health = Json.parseToJsonElement(body).jsonObject

        assertEquals("true", health["healthy"]?.jsonPrimitive?.content)
        assertEquals(OpenCodeFixtures.SERVER_VERSION, health["version"]?.jsonPrimitive?.content)
    }

    @Test
    fun sessionListReturnsDeterministicFixtures() = runTest {
        server.start()
        val body = server.client.get("${server.baseUrl}${MockOpenCodeServer.SESSION_PATH}").bodyAsText()
        val sessions = Json.parseToJsonElement(body).jsonArray

        assertEquals(2, sessions.size)
        assertEquals("ses_mock_0001", sessions[0].jsonObject["id"]?.jsonPrimitive?.content)
        assertEquals("ses_mock_0002", sessions[1].jsonObject["id"]?.jsonPrimitive?.content)
        assertEquals(OpenCodeFixtures.DIRECTORY, sessions[0].jsonObject["directory"]?.jsonPrimitive?.content)
    }

    @Test
    fun createdSessionsAppearInTheListDeterministically() = runTest {
        server.start()
        server.client.post("${server.baseUrl}${MockOpenCodeServer.SESSION_PATH}")
        val body = server.client.get("${server.baseUrl}${MockOpenCodeServer.SESSION_PATH}").bodyAsText()
        val sessions = Json.parseToJsonElement(body).jsonArray

        assertEquals(3, sessions.size)
        assertEquals("ses_mock_1001", sessions[2].jsonObject["id"]?.jsonPrimitive?.content)
        assertTrue(server.requests.contains("POST /session"))
    }

    @Test
    fun scriptedSseEventsAreDeliveredInOrder() = runTest {
        server.start()
        val raw = server.client.get("${server.baseUrl}${MockOpenCodeServer.EVENT_PATH}").bodyAsText()
        val events = MockSseCodec.decode(raw)

        assertEquals(listOf("evt_mock_0001", "evt_mock_0002"), events.map { it.id })
        assertEquals("session.updated", events[0].type)
        assertEquals("message.part.updated", events[1].type)
        assertTrue(events[1].data.contains("Streaming from the mock server."))
    }

    @Test
    fun unsupportedVersionScenarioReportsAnIncompatibleVersion() = runTest {
        val old = MockOpenCodeServer(MockOpenCodeScenario.UnsupportedVersion).start()
        try {
            val body = old.client.get("${old.baseUrl}${MockOpenCodeServer.HEALTH_PATH}").bodyAsText()
            val health = Json.parseToJsonElement(body).jsonObject
            assertEquals(
                OpenCodeFixtures.UNSUPPORTED_SERVER_VERSION,
                health["version"]?.jsonPrimitive?.content,
            )
        } finally {
            old.stop()
        }
    }

    @Test
    fun authenticationFailureScenarioReturnsUnauthorized() = runTest {
        val locked = MockOpenCodeServer(MockOpenCodeScenario.AuthenticationFailure).start()
        try {
            val response = locked.client.get("${locked.baseUrl}${MockOpenCodeServer.SESSION_PATH}")
            assertEquals(401, response.status.value)
        } finally {
            locked.stop()
        }
    }

    @Test
    fun serverErrorScenarioReturnsATypedErrorBody() = runTest {
        val failing = MockOpenCodeServer(MockOpenCodeScenario.ServerError).start()
        try {
            val response = failing.client.get("${failing.baseUrl}${MockOpenCodeServer.SESSION_PATH}")
            assertEquals(500, response.status.value)
            val error = Json.parseToJsonElement(response.bodyAsText()).jsonObject["error"]!!.jsonObject
            assertEquals("server_error", error["type"]?.jsonPrimitive?.content)
        } finally {
            failing.stop()
        }
    }

    @Test
    fun malformedEventScenarioPreservesTheTruncatedPayload() = runTest {
        val malformed = MockOpenCodeServer(MockOpenCodeScenario.MalformedEvent).start()
        try {
            val raw = malformed.client.get("${malformed.baseUrl}${MockOpenCodeServer.EVENT_PATH}").bodyAsText()
            val events = MockSseCodec.decode(raw)

            assertEquals(2, events.size)
            assertEquals("evt_mock_0001", events[0].id)
            assertEquals("evt_mock_bad", events[1].id)
            assertEquals(
                """{"type":"message.part.updated","properties":{"part":""",
                events[1].data,
            )
            assertFailsWith<SerializationException> { Json.parseToJsonElement(events[1].data) }
        } finally {
            malformed.stop()
        }
    }
}