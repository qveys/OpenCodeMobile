package org.opencodemobile.shared.testsupport

import io.ktor.client.plugins.skipSavingBody
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * End-to-end example that proves the [MockOpenCodeServer] harness works.
 *
 * The test drives the mock through a real Ktor client stack (request routing, headers and
 * body decoding all run) but no socket is opened: the engine is in-process. It covers the
 * baseline routes (health, session list, scripted SSE) and has one test per
 * [MockOpenCodeScenario], including the streaming, disconnect, reconnect, permission-request,
 * slow-network and long-transcript scenarios added for the MVP acceptance run (OPE-115).
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
    fun streamingScenarioDeliversEventsProgressively() = runTest {
        val streaming = MockOpenCodeServer(MockOpenCodeScenario.Streaming).start()
        try {
            val expected = OpenCodeFixtures.streamingSseEvents()

            // `client.get` downloads the whole body before returning, so this scenario uses the
            // streaming syntax: the block reads the body while it is still being produced.
            streaming.client
                .prepareGet("${streaming.baseUrl}${MockOpenCodeServer.EVENT_PATH}")
                .execute { response ->
                    val channel = response.bodyAsChannel()

                    val first = readEvent(channel)
                    assertEquals(expected[0].id, first?.id)

                    // The second event is held back by the inter-event delay, so consuming the
                    // body is genuinely progressive rather than one concatenated block.
                    assertNull(withTimeoutOrNull(30) { readEvent(channel) })

                    assertEquals(expected[1].id, readEvent(channel)?.id)
                    assertEquals(expected[2].id, readEvent(channel)?.id)
                    assertNull(readEvent(channel))
                }
        } finally {
            streaming.stop()
        }
    }

    @Test
    fun slowNetworkScenarioTakesAtLeastOneInterEventDelay() = runTest {
        val slow = MockOpenCodeServer(MockOpenCodeScenario.SlowNetwork).start()
        try {
            val started = TimeSource.Monotonic.markNow()
            val raw = slow.client.get("${slow.baseUrl}${MockOpenCodeServer.EVENT_PATH}") { skipSavingBody() }.bodyAsText()
            val elapsed = started.elapsedNow().inWholeMilliseconds
            val events = MockSseCodec.decode(raw)

            assertEquals(3, events.size)
            assertTrue(
                elapsed >= slow.streamConfig.slowNetworkDelayMillis,
                "expected at least one inter-event delay (${slow.streamConfig.slowNetworkDelayMillis}ms), took ${elapsed}ms",
            )
        } finally {
            slow.stop()
        }
    }

    @Test
    fun disconnectScenarioStopsBeforeTheLastEvent() = runTest {
        val disconnected = MockOpenCodeServer(MockOpenCodeScenario.Disconnect).start()
        try {
            val script = OpenCodeFixtures.disconnectSseEvents()
            val channel = disconnected.client
                .get("${disconnected.baseUrl}${MockOpenCodeServer.EVENT_PATH}") { skipSavingBody() }
                .bodyAsChannel()
            val received = readEvents(channel)

            assertEquals(disconnected.streamConfig.disconnectAfterEvents, received.size)
            assertTrue(received.size < script.size, "the stream must stop before the last scripted event")
            assertEquals(script[0].id, received[0].id)
            assertFalse(
                received.any { it.id == script.last().id },
                "the last scripted event must never arrive on a disconnected stream",
            )
        } finally {
            disconnected.stop()
        }
    }

    @Test
    fun reconnectScenarioResumesWithoutReplayingConsumedEvents() = runTest {
        val reconnect = MockOpenCodeServer(MockOpenCodeScenario.Reconnect).start()
        try {
            val script = OpenCodeFixtures.disconnectSseEvents()

            val first = readEvents(
                reconnect.client
                    .get("${reconnect.baseUrl}${MockOpenCodeServer.EVENT_PATH}") { skipSavingBody() }
                    .bodyAsChannel(),
            )
            assertEquals(reconnect.streamConfig.disconnectAfterEvents, first.size)

            val second = readEvents(
                reconnect.client
                    .get("${reconnect.baseUrl}${MockOpenCodeServer.EVENT_PATH}") { skipSavingBody() }
                    .bodyAsChannel(),
            )
            assertTrue(second.isNotEmpty())
            val consumedIds = first.map { it.id }.toSet()
            assertTrue(
                second.none { it.id in consumedIds },
                "reconnect must not replay already-consumed events",
            )
            assertEquals(script.map { it.id }, (first + second).map { it.id })
        } finally {
            reconnect.stop()
        }
    }

    @Test
    fun permissionRequestScenarioProvidesEventAndDecisionEndpoint() = runTest {
        val permission = MockOpenCodeServer(MockOpenCodeScenario.PermissionRequest).start()
        try {
            val events = MockSseCodec.decode(
                permission.client.get("${permission.baseUrl}${MockOpenCodeServer.EVENT_PATH}") { skipSavingBody() }
                    .bodyAsText(),
            )
            val asked = events.single { it.type == "permission.asked" }
            val properties = Json.parseToJsonElement(asked.data).jsonObject["properties"]!!.jsonObject
            val requestID = properties["id"]!!.jsonPrimitive.content
            assertEquals(OpenCodeFixtures.PERMISSION_REQUEST_ID, requestID)

            val pending = permission.client
                .get("${permission.baseUrl}${MockOpenCodeServer.PERMISSION_PATH}")
                .bodyAsText()
            assertTrue(pending.contains(OpenCodeFixtures.PERMISSION_REQUEST_ID))

            val questions = permission.client
                .get("${permission.baseUrl}${MockOpenCodeServer.QUESTION_PATH}")
                .bodyAsText()
            assertTrue(questions.contains(OpenCodeFixtures.QUESTION_REQUEST_ID))

            val response = permission.client.post(
                "${permission.baseUrl}${MockOpenCodeServer.PERMISSION_PATH}/$requestID/reply",
            ) {
                contentType(ContentType.Application.Json)
                setBody(TextContent("""{"reply":"once"}""", ContentType.Application.Json))
            }
            assertEquals(200, response.status.value)
            assertTrue(Json.parseToJsonElement(response.bodyAsText()).jsonPrimitive.boolean)
            assertEquals("once", permission.permissionReplies.single().reply)
            assertTrue(permission.requests.contains("POST /permission/$requestID/reply"))

            val reject = permission.client.post(
                "${permission.baseUrl}${MockOpenCodeServer.QUESTION_PATH}/" +
                    "${OpenCodeFixtures.QUESTION_REQUEST_ID}/reject",
            )
            assertEquals(200, reject.status.value)
            assertEquals("reject", permission.questionReplies.single().decision)
        } finally {
            permission.stop()
        }
    }

    @Test
    fun longTranscriptScenarioStreamsHundredsOfParts() = runTest {
        val transcript = MockOpenCodeServer(MockOpenCodeScenario.LongTranscript).start()
        try {
            val raw = transcript.client
                .get("${transcript.baseUrl}${MockOpenCodeServer.EVENT_PATH}") { skipSavingBody() }
                .bodyAsText()
            val events = MockSseCodec.decode(raw)

            assertEquals(1 + transcript.streamConfig.longTranscriptPartCount, events.size)
            assertEquals("message.part.updated", events.last().type)
            assertEquals("evt_mock_part_0600", events.last().id)
        } finally {
            transcript.stop()
        }
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

    @Test
    fun sessionStatusRouteReturnsThePinnedStatusMap() = runTest {
        server.start()
        val body = server.client.get("${server.baseUrl}${MockOpenCodeServer.SESSION_STATUS_PATH}").bodyAsText()
        val statuses = Json.parseToJsonElement(body).jsonObject

        assertEquals(OpenCodeFixtures.sessions.size, statuses.size)
        val active = statuses.getValue(OpenCodeFixtures.sessions.first().id).jsonObject
        assertEquals("retry", active["type"]?.jsonPrimitive?.content)
        assertEquals(OpenCodeFixtures.SESSION_STATUS_RETRY_ATTEMPT, active["attempt"]?.jsonPrimitive?.int)
        assertEquals(OpenCodeFixtures.SESSION_STATUS_RETRY_MESSAGE, active["message"]?.jsonPrimitive?.content)
        assertEquals(OpenCodeFixtures.SESSION_STATUS_RETRY_NEXT, active["next"]?.jsonPrimitive?.long)

        val idle = statuses.getValue(OpenCodeFixtures.sessions[1].id).jsonObject
        assertEquals("idle", idle["type"]?.jsonPrimitive?.content)
        assertTrue(server.requests.contains("GET ${MockOpenCodeServer.SESSION_STATUS_PATH}"))
    }

    @Test
    fun stayingDownServerFailsConsecutiveAttemptsBeforeResuming() = runTest {
        val config = MockOpenCodeStreamConfig(disconnectAfterEvents = 0, disconnectConnections = 3)
        val down = MockOpenCodeServer(MockOpenCodeScenario.Reconnect, streamConfig = config).start()
        try {
            val script = OpenCodeFixtures.disconnectSseEvents()
            repeat(config.disconnectConnections) { attempt ->
                val events = readEvents(
                    down.client
                        .get("${down.baseUrl}${MockOpenCodeServer.EVENT_PATH}") { skipSavingBody() }
                        .bodyAsChannel(),
                )
                assertTrue(events.isEmpty(), "down attempt ${attempt + 1} must deliver no events")
            }

            val resumed = readEvents(
                down.client
                    .get("${down.baseUrl}${MockOpenCodeServer.EVENT_PATH}") { skipSavingBody() }
                    .bodyAsChannel(),
            )
            assertEquals(script.map { it.id }, resumed.map { it.id })
            assertEquals(config.disconnectConnections + 1, down.eventStreamConnections)
        } finally {
            down.stop()
        }
    }

    @Test
    fun reconnectingClientReceivesTailEventsOnANonClosingConnection() = runTest {
        val tail = OpenCodeFixtures.liveTailSseEvents()
        val config = MockOpenCodeStreamConfig(
            disconnectAfterEvents = 2,
            tailEvents = tail,
            tailDelayMillis = 30L,
            keepOpenMillis = 200L,
        )
        val server = MockOpenCodeServer(MockOpenCodeScenario.Reconnect, streamConfig = config).start()
        try {
            val script = OpenCodeFixtures.disconnectSseEvents()
            val truncated = readEvents(
                server.client
                    .get("${server.baseUrl}${MockOpenCodeServer.EVENT_PATH}") { skipSavingBody() }
                    .bodyAsChannel(),
            )
            assertEquals(config.disconnectAfterEvents, truncated.size)

            server.client
                .prepareGet("${server.baseUrl}${MockOpenCodeServer.EVENT_PATH}")
                .execute { response ->
                    val channel = response.bodyAsChannel()
                    // The reconnect first drains the remaining backlog...
                    assertEquals(script[2].id, readEvent(channel)?.id)
                    assertEquals(script[3].id, readEvent(channel)?.id)
                    // ...the tail event is emitted later, on a channel that stays open.
                    assertNull(withTimeoutOrNull(10) { readEvent(channel) })
                    assertEquals(tail.single().id, readEvent(channel)?.id)
                }
            assertEquals(2, server.eventStreamConnections)
        } finally {
            server.stop()
        }
    }

    @Test
    fun keepOpenMillisKeepsTheEventBodyOpenAfterTheScript() = runTest {
        val config = MockOpenCodeStreamConfig(keepOpenMillis = 120L)
        val server = MockOpenCodeServer(MockOpenCodeScenario.Default, streamConfig = config).start()
        try {
            val started = TimeSource.Monotonic.markNow()
            val raw = server.client
                .get("${server.baseUrl}${MockOpenCodeServer.EVENT_PATH}") { skipSavingBody() }
                .bodyAsText()
            val elapsed = started.elapsedNow().inWholeMilliseconds

            assertEquals(OpenCodeFixtures.defaultSseEvents().size, MockSseCodec.decode(raw).size)
            assertTrue(
                elapsed >= config.keepOpenMillis,
                "expected the body to stay open ${config.keepOpenMillis}ms, ended after ${elapsed}ms",
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun replayedEventIdIsDeliveredAgainForDeduplication() = runTest {
        val script = OpenCodeFixtures.disconnectSseEvents()
        val replayedId = script[1].id
        val config = MockOpenCodeStreamConfig(disconnectAfterEvents = 2, replayFromEventId = replayedId)
        val server = MockOpenCodeServer(MockOpenCodeScenario.Reconnect, streamConfig = config).start()
        try {
            val first = readEvents(
                server.client
                    .get("${server.baseUrl}${MockOpenCodeServer.EVENT_PATH}") { skipSavingBody() }
                    .bodyAsChannel(),
            )
            val consumed = first.map { it.id }.toSet()
            val second = readEvents(
                server.client
                    .get("${server.baseUrl}${MockOpenCodeServer.EVENT_PATH}") { skipSavingBody() }
                    .bodyAsChannel(),
            )

            assertEquals(replayedId, second.first().id)
            assertTrue(second.first().id in consumed, "the replayed id must already have been consumed")

            val delivered = (first + second).map { it.id }
            assertEquals(script.size + 1, delivered.size, "the server must replay exactly one consumed id")
            // OPE-106 EventProcessor contract: drop replayed/duplicate events by server-issued id.
            assertEquals(script.map { it.id }, delivered.distinct())
        } finally {
            server.stop()
        }
    }

    /** Reads one SSE record, or `null` at end of stream. */
    private suspend fun readEvent(channel: ByteReadChannel): MockSseEvent? {
        val lines = mutableListOf<String>()
        while (true) {
            val line = channel.readUTF8Line()
            if (line == null) {
                return if (lines.isEmpty()) null else decodeEvent(lines)
            }
            if (line.isEmpty()) {
                return decodeEvent(lines)
            }
            lines += line
        }
    }

    private fun decodeEvent(lines: List<String>): MockSseEvent? =
        MockSseCodec.decode(lines.joinToString("\n") + "\n").firstOrNull()

    private suspend fun readEvents(channel: ByteReadChannel): List<MockSseEvent> {
        val events = mutableListOf<MockSseEvent>()
        while (true) {
            val event = readEvent(channel) ?: break
            events += event
        }
        return events
    }
}
