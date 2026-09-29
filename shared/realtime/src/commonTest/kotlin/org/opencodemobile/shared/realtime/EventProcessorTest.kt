package org.opencodemobile.shared.realtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.opencodemobile.shared.domain.event.ConnectionPhase
import org.opencodemobile.shared.domain.event.EventTransport
import org.opencodemobile.shared.domain.event.RawServerEvent
import org.opencodemobile.shared.domain.event.RealtimeSnapshot
import org.opencodemobile.shared.domain.event.ServerEvent
import org.opencodemobile.shared.domain.event.SessionSnapshot
import org.opencodemobile.shared.domain.event.SessionStatus
import org.opencodemobile.shared.domain.event.UserMutation

/**
 * Deterministic pipeline tests driven by a scripted [EventTransport].
 *
 * Virtual time makes the timing-sensitive lines exact: inactivity fallback, bounded
 * polling backoff and bounded reconnect backoff are asserted at the millisecond.
 * The [EventProcessorMockServerTest] integration suite proves the same pipeline against
 * the real networking transport and `MockOpenCodeServer`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EventProcessorTest {

    private val session = SessionSnapshot(id = "ses_mock_0001", title = "One", directory = "/tmp", updatedAt = 1L)

    @Test
    fun nominalSseCycleAppliesSnapshotThenEvents() = runTest {
        val transport = FakeEventTransport { testScheduler.currentTime }
        transport.snapshot = RealtimeSnapshot(
            sessions = listOf(session),
            statuses = mapOf(session.id to SessionStatus("retry", attempt = 2, message = "waiting", next = 99L)),
        )
        transport.stream = {
            flow {
                emit(RawServerEvent("evt_mock_0001", "session.updated", """{"type":"session.updated"}"""))
                emit(RawServerEvent("evt_mock_0002", "message.part.updated", """{"type":"message.part.updated"}"""))
                awaitCancellation()
            }
        }

        val received = mutableListOf<ServerEvent>()
        val processor = EventProcessor(transport)
        backgroundScope.launch { processor.events.collect { received += it } }
        runCurrent()

        processor.start(backgroundScope)
        runCurrent()

        assertEquals(ConnectionPhase.Live, processor.state.value.phase)
        assertEquals(listOf(session.id), processor.state.value.sessions.map { it.id })
        assertEquals("retry", processor.state.value.statuses[session.id]?.type)
        assertEquals(2L, processor.state.value.eventCount)
        assertEquals("evt_mock_0002", processor.state.value.lastEventId)
        assertEquals(listOf("evt_mock_0001", "evt_mock_0002"), received.map { it.id })
        assertEquals(listOf(1L, 2L), received.map { it.sequence })
        assertEquals(listOf(0L), transport.snapshotAt, "snapshot precedes the stream")
        assertEquals(listOf(0L), transport.streamAt)

        processor.stop()
        assertEquals(ConnectionPhase.Stopped, processor.state.value.phase)
    }

    @Test
    fun prematureSseCloseFallsBackToPolling() = runTest {
        val transport = FakeEventTransport { testScheduler.currentTime }
        transport.snapshot = RealtimeSnapshot(listOf(session), emptyMap())
        transport.polledStatuses = mapOf(session.id to SessionStatus("idle"))
        // The body ends after one event: a premature SSE close.
        transport.stream = {
            flow { emit(RawServerEvent("evt_mock_0001", "session.updated", """{}""")) }
        }

        val processor = EventProcessor(transport)
        processor.start(backgroundScope)
        runCurrent()

        assertTrue(transport.pollAt.isNotEmpty(), "a premature close must degrade to polling")
        assertEquals(listOf(0L), transport.pollAt)
        assertEquals(listOf(session.id), processor.state.value.statuses.keys.toList())

        processor.stop()
    }

    @Test
    fun inactivityTimeoutFallsBackToPolling() = runTest {
        val transport = FakeEventTransport { testScheduler.currentTime }
        transport.snapshot = RealtimeSnapshot(listOf(session), emptyMap())
        transport.stream = {
            flow {
                emit(RawServerEvent("evt_mock_0001", "session.updated", """{}"""))
                awaitCancellation() // healthy but idle: no further event
            }
        }
        val config = EventProcessorConfig(
            sseInactivityTimeoutMillis = 1_000L,
            reconnectInitialBackoffMillis = 100L,
        )

        val processor = EventProcessor(transport, config)
        processor.start(backgroundScope)
        runCurrent()
        assertEquals(0, transport.pollAt.size, "the idle grace period has not elapsed yet")

        advanceTimeBy(1_001)
        runCurrent()

        assertTrue(transport.pollAt.isNotEmpty(), "an idle stream past the timeout must poll")
        processor.stop()
    }

    @Test
    fun malformedEventIsDroppedAndPipelineStaysAlive() = runTest {
        val transport = FakeEventTransport { testScheduler.currentTime }
        transport.snapshot = RealtimeSnapshot(listOf(session), emptyMap())
        transport.stream = {
            flow {
                emit(RawServerEvent("evt_mock_0001", "session.updated", """{"type":"session.updated"}"""))
                emit(
                    RawServerEvent(
                        "evt_mock_bad",
                        "message.part.updated",
                        """{"type":"message.part.updated","properties":{"part":""",
                    ),
                )
                emit(RawServerEvent("evt_mock_0003", "message.part.updated", """{"ok":true}"""))
                awaitCancellation()
            }
        }

        val received = mutableListOf<ServerEvent>()
        val processor = EventProcessor(transport)
        backgroundScope.launch { processor.events.collect { received += it } }
        runCurrent()
        processor.start(backgroundScope)
        runCurrent()

        assertEquals(listOf("evt_mock_0001", "evt_mock_0003"), received.map { it.id })
        assertEquals(ConnectionPhase.Live, processor.state.value.phase, "the pipeline must survive a malformed event")

        processor.stop()
    }

    @Test
    fun duplicateServerEventIdsAreDropped() = runTest {
        val transport = FakeEventTransport { testScheduler.currentTime }
        transport.snapshot = RealtimeSnapshot(listOf(session), emptyMap())
        transport.stream = {
            flow {
                emit(RawServerEvent("evt_dup", "session.updated", """{"n":1}"""))
                emit(RawServerEvent("evt_dup", "session.updated", """{"n":1}"""))
                awaitCancellation()
            }
        }

        val received = mutableListOf<ServerEvent>()
        val processor = EventProcessor(transport)
        backgroundScope.launch { processor.events.collect { received += it } }
        runCurrent()
        processor.start(backgroundScope)
        runCurrent()

        assertEquals(1, received.size, "a replayed server id must be dropped")
        assertEquals(1L, processor.state.value.eventCount)

        processor.stop()
    }

    @Test
    fun reconnectRefetchesSnapshotAndServerWins() = runTest {
        val transport = FakeEventTransport { testScheduler.currentTime }
        val first = RealtimeSnapshot(listOf(session), emptyMap())
        val second = RealtimeSnapshot(
            listOf(session.copy(title = "Renamed by the server")),
            mapOf(session.id to SessionStatus("idle")),
        )
        transport.snapshot = first
        transport.stream = {
            // The first stream ends immediately, forcing a reconnect; the second stays open.
            if (transport.streamAt.size > 1) {
                flow { emit(RawServerEvent("evt_late", "session.updated", """{}""")); awaitCancellation() }
            } else {
                emptyFlow()
            }
        }

        val processor = EventProcessor(transport)
        processor.start(backgroundScope)
        runCurrent()
        transport.snapshot = second

        advanceTimeBy(1_100) // past the grown reconnect backoff (the zero-event stream grows it)
        runCurrent()

        assertTrue(transport.snapshotAt.size >= 2, "a reconnect must refetch the snapshot")
        assertEquals(listOf("Renamed by the server"), processor.state.value.sessions.map { it.title })
        assertEquals(2, transport.streamAt.size, "the resumed stream was opened after the second snapshot")

        processor.stop()
    }

    @Test
    fun reconnectBackoffIsExponentialAndBounded() = runTest {
        val transport = FakeEventTransport { testScheduler.currentTime }
        transport.snapshotFailuresRemaining = Int.MAX_VALUE

        val processor = EventProcessor(transport)
        processor.start(backgroundScope)
        runCurrent()

        // 500 initial, x2 each failure, capped at 30_000.
        advanceTimeBy(500); runCurrent()
        advanceTimeBy(1_000); runCurrent()
        advanceTimeBy(2_000); runCurrent()
        advanceTimeBy(4_000); runCurrent()
        advanceTimeBy(8_000); runCurrent()
        advanceTimeBy(16_000); runCurrent()
        advanceTimeBy(30_000); runCurrent()
        advanceTimeBy(30_000); runCurrent()

        assertEquals(
            listOf(0L, 500L, 1_500L, 3_500L, 7_500L, 15_500L, 31_500L, 61_500L, 91_500L),
            transport.snapshotAt,
        )
        val intervals = transport.snapshotAt.zipWithNext { previous, next -> next - previous }
        assertTrue(intervals.all { it <= 30_000L }, "reconnect backoff must stay bounded: $intervals")
        assertEquals(30_000L, intervals.last())

        processor.stop()
    }

    @Test
    fun pollingBackoffIsExponentialAndBounded() = runTest {
        val transport = FakeEventTransport { testScheduler.currentTime }
        transport.snapshot = RealtimeSnapshot(listOf(session), emptyMap())
        transport.stream = { emptyFlow() } // close immediately -> polling
        transport.pollAlwaysFails = true
        val config = EventProcessorConfig(
            reconnectInitialBackoffMillis = 100L,
            pollInitialIntervalMillis = 1_000L,
            pollMaxBackoffMillis = 4_000L,
        )

        val processor = EventProcessor(transport, config)
        processor.start(backgroundScope)
        runCurrent()

        advanceTimeBy(1_000); runCurrent()
        advanceTimeBy(2_000); runCurrent()
        advanceTimeBy(4_000); runCurrent()
        advanceTimeBy(4_000); runCurrent()

        assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L, 11_000L), transport.pollAt)
        val intervals = transport.pollAt.zipWithNext { previous, next -> next - previous }
        assertTrue(intervals.all { it <= 4_000L }, "polling backoff must stay bounded: $intervals")

        processor.stop()
    }

    @Test
    fun streamFlappingBackoffGrowsEvenWhenTheSnapshotStaysHealthy() = runTest {
        val transport = FakeEventTransport { testScheduler.currentTime }
        transport.snapshot = RealtimeSnapshot(listOf(session), emptyMap())
        // The JSON routes always answer, but /event closes immediately every time (0 events).
        transport.stream = { emptyFlow() }
        val config = EventProcessorConfig(
            reconnectInitialBackoffMillis = 500L,
            reconnectMaxBackoffMillis = 8_000L,
        )

        val processor = EventProcessor(transport, config)
        processor.start(backgroundScope)
        runCurrent()

        advanceTimeBy(1_000); runCurrent()
        advanceTimeBy(2_000); runCurrent()
        advanceTimeBy(4_000); runCurrent()
        advanceTimeBy(8_000); runCurrent()
        advanceTimeBy(8_000); runCurrent()

        assertEquals(
            listOf(0L, 1_000L, 3_000L, 7_000L, 15_000L, 23_000L),
            transport.streamAt,
            "a flapping /event must not reconnect at a fixed interval",
        )
        val intervals = transport.streamAt.zipWithNext { previous, next -> next - previous }
        assertTrue(intervals.all { it <= 8_000L }, "reconnect backoff must stay bounded: $intervals")
        assertTrue(
            intervals.zipWithNext().all { (first, second) -> second >= first },
            "the reconnect interval must never shrink: $intervals",
        )
        assertEquals(8_000L, intervals.last())

        processor.stop()
    }

    @Test
    fun polledStatusesReconcileWithoutLosingRetryState() = runTest {
        val transport = FakeEventTransport { testScheduler.currentTime }
        transport.snapshot = RealtimeSnapshot(listOf(session), emptyMap())
        transport.polledStatuses = mapOf(
            session.id to SessionStatus("retry", attempt = 2, message = "rate limited", next = 5_000L),
        )
        transport.stream = { emptyFlow() }

        val processor = EventProcessor(transport)
        processor.start(backgroundScope)
        runCurrent()

        val polled = processor.state.value.statuses.getValue(session.id)
        assertEquals("retry", polled.type)
        assertEquals(2, polled.attempt)
        assertEquals("rate limited", polled.message)

        processor.stop()
    }

    @Test
    fun failedMutationIsNeverQueuedOrReplayed() = runTest {
        val transport = FakeEventTransport { testScheduler.currentTime }
        transport.snapshot = RealtimeSnapshot(emptyList(), emptyMap())
        transport.stream = {
            flow { emit(RawServerEvent("evt_mock_0001", "session.updated", """{}""")); awaitCancellation() }
        }

        val sender = FailFastMutationSender()
        var attempts = 0

        val processor = EventProcessor(transport)
        processor.start(backgroundScope)
        runCurrent()

        val outcome = sender.send(
            UserMutation {
                attempts += 1
                error("the user action failed")
            },
        )

        assertTrue(outcome.isFailure, "a failed mutation must fail, not be queued")
        assertEquals(1, attempts)

        // A full reconnect cycle must not replay the failed mutation (D9).
        processor.stop()
        processor.start(backgroundScope)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, attempts, "the mutation must never be replayed after a reconnect")

        processor.stop()
    }

    @Test
    fun mutationCancellationPropagatesInsteadOfBecomingAFailure() = runTest {
        val sender = FailFastMutationSender()
        val cancellation = CancellationException("caller scope cancelled")

        val outcome = runCatching {
            sender.send(UserMutation { throw cancellation })
        }

        assertTrue(outcome.isFailure)
        assertSame(
            cancellation,
            outcome.exceptionOrNull(),
            "cancellation must be rethrown, not converted into a mutation failure",
        )
    }

    /** Scripted [EventTransport] whose call timestamps use the test scheduler's virtual clock. */
    private class FakeEventTransport(
        private val now: () -> Long,
    ) : EventTransport {
        var snapshot: RealtimeSnapshot = RealtimeSnapshot(emptyList(), emptyMap())
        var snapshotFailuresRemaining: Int = 0
        var polledStatuses: Map<String, SessionStatus> = emptyMap()
        var pollAlwaysFails: Boolean = false
        var stream: () -> Flow<RawServerEvent> = { emptyFlow() }

        val snapshotAt = mutableListOf<Long>()
        val pollAt = mutableListOf<Long>()
        val streamAt = mutableListOf<Long>()

        override suspend fun fetchSnapshot(): RealtimeSnapshot {
            snapshotAt += now()
            if (snapshotFailuresRemaining > 0) {
                snapshotFailuresRemaining -= 1
                error("snapshot transport is down")
            }
            return snapshot
        }

        override suspend fun pollStatuses(): Map<String, SessionStatus> {
            pollAt += now()
            if (pollAlwaysFails) error("poll transport is down")
            return polledStatuses
        }

        override fun openEventStream(): Flow<RawServerEvent> {
            streamAt += now()
            return stream()
        }
    }
}
