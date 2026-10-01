package org.opencodemobile.shared.realtime

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.opencodemobile.shared.domain.event.ServerEvent
import org.opencodemobile.shared.networking.realtime.NetworkingRealtimeTransport
import org.opencodemobile.shared.testsupport.MockOpenCodeScenario
import org.opencodemobile.shared.testsupport.MockOpenCodeServer
import org.opencodemobile.shared.testsupport.MockOpenCodeStreamConfig
import org.opencodemobile.shared.testsupport.OpenCodeFixtures

/**
 * End-to-end pipeline tests: [EventProcessor] + [NetworkingRealtimeTransport] +
 * [MockOpenCodeServer].
 *
 * These run on [Dispatchers.Default] so the mock's inter-event delays are real time,
 * exactly like the transport tests in `shared:test-support`. They cover the five
 * scenarios the OPE-106 acceptance criteria name: `streaming`, `disconnect`,
 * `reconnect`, `malformed-event` and `slow-network`.
 */
class EventProcessorMockServerTest {

    private val servers = mutableListOf<MockOpenCodeServer>()
    private val scopes = mutableListOf<CoroutineScope>()

    @AfterTest
    fun tearDown() {
        scopes.forEach { it.cancel() }
        servers.forEach { it.stop() }
    }

    private fun startServer(
        scenario: MockOpenCodeScenario,
        streamConfig: MockOpenCodeStreamConfig = MockOpenCodeStreamConfig(),
    ): MockOpenCodeServer =
        MockOpenCodeServer(scenario = scenario, streamConfig = streamConfig).start().also { servers += it }

    private fun newScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }

    private fun transportFor(server: MockOpenCodeServer): NetworkingRealtimeTransport =
        NetworkingRealtimeTransport(httpClient = server.client, baseUrl = server.baseUrl)

    /** Real-time wait so the mock's real inter-event delays are honoured. */
    private suspend fun awaitUntil(timeoutMillis: Long = 4_000L, condition: () -> Boolean) {
        withTimeout(timeoutMillis) {
            while (!condition()) delay(5L)
        }
    }

    private suspend fun drainUntil(
        processor: EventProcessor,
        scope: CoroutineScope,
        expected: Int,
        timeoutMillis: Long = 4_000L,
    ): List<ServerEvent> {
        val received = mutableListOf<ServerEvent>()
        scope.launch { processor.events.collect { received += it } }
        processor.start(scope)
        awaitUntil(timeoutMillis) { received.size >= expected }
        return received
    }

    @Test
    fun streamingScenarioDeliversEventsThroughTheProcessor() = runTest {
        val server = startServer(MockOpenCodeScenario.Streaming)
        val scope = newScope()
        val expected = OpenCodeFixtures.streamingSseEvents()
        val processor = EventProcessor(transportFor(server))

        withContext(Dispatchers.Default) {
            val received = drainUntil(processor, scope, expected.size)
            assertEquals(expected.map { it.id }, received.map { it.id })
            assertEquals(expected.size.toLong(), processor.state.value.eventCount)
            assertTrue(processor.state.value.sessions.isNotEmpty(), "the snapshot must be applied first")
        }
        processor.stop()
    }

    @Test
    fun disconnectScenarioFallsBackToPollingAndResumes() = runTest {
        val server = startServer(MockOpenCodeScenario.Disconnect)
        val scope = newScope()
        val processor = EventProcessor(transportFor(server))

        withContext(Dispatchers.Default) {
            processor.start(scope)
            awaitUntil { server.requests.contains("GET ${MockOpenCodeServer.SESSION_STATUS_PATH}") }
            awaitUntil { server.eventStreamConnections >= 2 }
            assertTrue(
                server.requests.count { it == "GET ${MockOpenCodeServer.SESSION_PATH}" } >= 2,
                "the reconnect after polling must refetch the snapshot",
            )
        }
        processor.stop()
    }

    @Test
    fun reconnectScenarioResumesFromSnapshotWithBoundedBackoff() = runTest {
        val streamConfig = MockOpenCodeStreamConfig(disconnectAfterEvents = 0, disconnectConnections = 2)
        val server = startServer(MockOpenCodeScenario.Reconnect, streamConfig)
        val script = OpenCodeFixtures.disconnectSseEvents()
        val scope = newScope()
        val processor = EventProcessor(transportFor(server))

        withContext(Dispatchers.Default) {
            val received = drainUntil(processor, scope, script.size, timeoutMillis = 6_000L)
            assertEquals(script.map { it.id }, received.map { it.id })
            assertTrue(
                server.eventStreamConnections >= 3,
                "two down attempts plus the resumed stream: ${server.eventStreamConnections}",
            )
            assertTrue(
                server.requests.count { it == "GET ${MockOpenCodeServer.SESSION_PATH}" } >= 3,
                "each reconnect attempt reconciles a fresh snapshot",
            )
        }
        processor.stop()
    }

    @Test
    fun malformedEventIsSkippedAndPipelineStaysAlive() = runTest {
        val tail = OpenCodeFixtures.liveTailSseEvents()
        val server = startServer(
            MockOpenCodeScenario.MalformedEvent,
            MockOpenCodeStreamConfig(tailEvents = tail),
        )
        val scope = newScope()
        val processor = EventProcessor(transportFor(server))

        withContext(Dispatchers.Default) {
            val received = drainUntil(processor, scope, expected = 2)
            assertEquals(
                listOf("evt_mock_0001", tail.single().id),
                received.map { it.id },
                "the malformed event is dropped and the pipeline keeps consuming the stream",
            )
        }
        processor.stop()
    }

    @Test
    fun slowNetworkScenarioStreamsThroughTheProcessor() = runTest {
        val server = startServer(MockOpenCodeScenario.SlowNetwork)
        val scope = newScope()
        val expected = OpenCodeFixtures.slowNetworkSseEvents()
        val processor = EventProcessor(transportFor(server))

        withContext(Dispatchers.Default) {
            val received = drainUntil(processor, scope, expected.size, timeoutMillis = 6_000L)
            assertEquals(expected.map { it.id }, received.map { it.id })
        }
        processor.stop()
    }
}
