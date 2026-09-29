package org.opencodemobile.shared.realtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.opencodemobile.shared.domain.event.ConnectionPhase
import org.opencodemobile.shared.domain.event.EventSource
import org.opencodemobile.shared.domain.event.EventTransport
import org.opencodemobile.shared.domain.event.MutationSender
import org.opencodemobile.shared.domain.event.RawServerEvent
import org.opencodemobile.shared.domain.event.RealtimeSnapshot
import org.opencodemobile.shared.domain.event.RealtimeState
import org.opencodemobile.shared.domain.event.ServerEvent
import org.opencodemobile.shared.domain.event.UserMutation

/**
 * Tunables for [EventProcessor]. Defaults are chosen to be safe on a flaky mobile
 * network: polling (and reconnect) back off with a floor, and every interval is
 * bounded so a degraded transport never spins hot.
 */
public data class EventProcessorConfig(
    /** How long the SSE stream may stay silent before the pipeline degrades to polling. */
    public val sseInactivityTimeoutMillis: Long = 30_000L,

    /** First delay before retrying the snapshot/SSE after a transport failure. */
    public val reconnectInitialBackoffMillis: Long = 500L,

    /** Upper bound for the reconnect backoff (never exceeded). */
    public val reconnectMaxBackoffMillis: Long = 30_000L,

    /** First interval between two fallback polls while the SSE is down. */
    public val pollInitialIntervalMillis: Long = 1_000L,

    /** Upper bound for the polling interval (never exceeded). */
    public val pollMaxBackoffMillis: Long = 30_000L,

    /** Multiplier applied to the reconnect and polling backoffs after each failure. */
    public val backoffMultiplier: Double = 2.0,

    /** Pending-event buffer for the hot [EventSource.events] flow. */
    public val eventBufferCapacity: Int = 64,

    /** How many server-issued ids the dedup filter remembers before evicting the oldest. */
    public val maxRememberedEventIds: Int = 4_096,
) {
    init {
        require(sseInactivityTimeoutMillis > 0L) { "sseInactivityTimeoutMillis must be > 0" }
        require(reconnectInitialBackoffMillis > 0L) { "reconnectInitialBackoffMillis must be > 0" }
        require(reconnectMaxBackoffMillis >= reconnectInitialBackoffMillis) {
            "reconnectMaxBackoffMillis must be >= reconnectInitialBackoffMillis"
        }
        require(pollInitialIntervalMillis > 0L) { "pollInitialIntervalMillis must be > 0" }
        require(pollMaxBackoffMillis >= pollInitialIntervalMillis) {
            "pollMaxBackoffMillis must be >= pollInitialIntervalMillis"
        }
        require(backoffMultiplier >= 1.0) { "backoffMultiplier must be >= 1.0" }
        require(eventBufferCapacity > 0) { "eventBufferCapacity must be > 0" }
        require(maxRememberedEventIds > 0) { "maxRememberedEventIds must be > 0" }
    }
}

/**
 * The single realtime event pipeline of a connection (`docs/ARCHITECTURE.md` §3.2).
 *
 * Sequence, on every (re)connection:
 *
 * 1. **Snapshot**: fetch the server snapshot and replace the local model with it.
 *    The server wins every conflict (D2); nothing local survives.
 * 2. **Stream**: open the SSE stream and apply normalized events on top of the
 *    snapshot. Events are ordered and deduplicated by the server-issued id.
 * 3. **Fallback**: if the stream ends early, is idle past the configured timeout, or
 *    fails, degrade to the mandatory polling transport (`GET /session/status`) with
 *    bounded exponential backoff, then go back to SSE once the transport is healthy.
 * 4. **Backoff**: a bounded delay sits between two SSE attempts so a server that
 *    keeps closing the stream cannot hot-loop.
 *
 * Invariants:
 * - A malformed payload is dropped without ending the pipeline.
 * - Replayed/duplicate server ids are dropped by the dedup filter.
 * - No user mutation is queued, retried, or replayed (D9); see [FailFastMutationSender].
 *
 * One instance is meant to serve one connection for its whole lifetime. It is
 * thread-safe for a single [start]/[stop] owner; concurrent [start] calls are a no-op.
 */
public class EventProcessor(
    private val transport: EventTransport,
    private val config: EventProcessorConfig = EventProcessorConfig(),
) : EventSource {

    private val json: Json = Json { ignoreUnknownKeys = true }

    private val mutableState = MutableStateFlow(RealtimeState())
    override val state: StateFlow<RealtimeState> = mutableState.asStateFlow()

    private val mutableEvents = MutableSharedFlow<ServerEvent>(
        replay = 0,
        extraBufferCapacity = config.eventBufferCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: SharedFlow<ServerEvent> = mutableEvents.asSharedFlow()

    private var pipelineJob: Job? = null
    private var sequence: Long = 0L
    private val seenEventIds = LinkedHashSet<String>()

    override fun start(scope: CoroutineScope) {
        if (pipelineJob?.isActive == true) return
        pipelineJob = scope.launch { runPipeline() }
    }

    override fun stop() {
        pipelineJob?.cancel()
        pipelineJob = null
        mutableState.value = mutableState.value.copy(phase = ConnectionPhase.Stopped)
    }

    /**
     * The pipeline loop. Runs until its scope is cancelled by [stop].
     *
     * Snapshot and SSE are attempted together: a failed snapshot is a transport
     * failure and backs off exactly like a failed stream.
     */
    private suspend fun runPipeline() {
        var backoff = config.reconnectInitialBackoffMillis

        while (currentCoroutineContext().isActive) {
            mutableState.value = mutableState.value.copy(phase = ConnectionPhase.Reconciling)
            val snapshot = fetchSnapshotOrNull()
            if (snapshot == null) {
                mutableState.value = mutableState.value.copy(phase = ConnectionPhase.Polling)
                delay(backoff)
                backoff = nextBackoff(backoff, config.reconnectMaxBackoffMillis)
                continue
            }

            applySnapshot(snapshot)
            mutableState.value = mutableState.value.copy(phase = ConnectionPhase.Live)

            val healthyStream = runSseSession()

            // Early close, inactivity or failure: the mandatory polling fallback.
            mutableState.value = mutableState.value.copy(phase = ConnectionPhase.Polling)
            pollUntilHealthy()

            // Reset the backoff only after a stream that actually delivered events. A
            // server that answers the snapshot but closes /event immediately (zero events)
            // keeps growing the backoff instead of flapping at a fixed interval.
            backoff = if (healthyStream) {
                config.reconnectInitialBackoffMillis
            } else {
                nextBackoff(backoff, config.reconnectMaxBackoffMillis)
            }

            // Bounded backoff before the next SSE attempt.
            delay(backoff)
        }
    }

    private suspend fun fetchSnapshotOrNull(): RealtimeSnapshot? =
        try {
            transport.fetchSnapshot()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            null
        }

    private fun applySnapshot(snapshot: RealtimeSnapshot) {
        // D2: the server wins; replace the local view entirely.
        mutableState.value = mutableState.value.copy(
            snapshot = snapshot,
            sessions = snapshot.sessions,
            statuses = snapshot.statuses,
        )
    }

    /**
     * Consumes one SSE session until it ends, times out, or fails. Never throws:
     * every ending is handled by the caller as "degrade to polling".
     *
     * @return true when the session delivered at least one normalized event, i.e. the
     *   transport was demonstrably healthy. The caller uses it to decide whether the
     *   reconnect backoff resets or keeps growing.
     */
    private suspend fun runSseSession(): Boolean = coroutineScope {
        val channel = Channel<RawServerEvent>(capacity = config.eventBufferCapacity)
        val producer = launch {
            try {
                transport.openEventStream().collect { channel.send(it) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                // Transport failure: closing the channel ends the session and falls back.
            } finally {
                channel.close()
            }
        }

        var acceptedAny = false
        try {
            while (true) {
                val raw = try {
                    withTimeoutOrNull(config.sseInactivityTimeoutMillis) { channel.receive() }
                } catch (closed: ClosedReceiveChannelException) {
                    break
                }
                if (raw == null) break // inactivity timeout -> polling fallback
                if (handleRawEvent(raw)) acceptedAny = true
            }
        } finally {
            producer.cancel()
        }
        acceptedAny
    }

    /**
     * Normalizes and accepts one raw record. A payload that is not valid JSON is
     * dropped silently: the malformed event never reaches [events] and the pipeline
     * keeps running. Duplicate server ids are dropped as well.
     *
     * @return true when the record produced a normalized event on [events].
     */
    private suspend fun handleRawEvent(raw: RawServerEvent): Boolean {
        if (!isWellFormed(raw.data)) return false

        val id = raw.id?.takeIf { it.isNotBlank() }
        if (id != null && isDuplicate(id)) return false

        sequence += 1
        val accepted = ServerEvent(
            sequence = sequence,
            id = id,
            type = raw.type,
            payload = raw.data,
        )
        mutableState.value = mutableState.value.copy(
            eventCount = sequence,
            lastEventId = id ?: mutableState.value.lastEventId,
        )
        mutableEvents.emit(accepted)
        return true
    }

    private fun isWellFormed(payload: String): Boolean =
        try {
            json.parseToJsonElement(payload)
            true
        } catch (malformed: SerializationException) {
            false
        }

    /** Returns true when [id] was already accepted, keeping the remembered window bounded. */
    private fun isDuplicate(id: String): Boolean {
        if (seenEventIds.contains(id)) return true
        seenEventIds.add(id)
        if (seenEventIds.size > config.maxRememberedEventIds) {
            val eldest = seenEventIds.iterator()
            if (eldest.hasNext()) {
                eldest.next()
                eldest.remove()
            }
        }
        return false
    }

    /**
     * Polls `GET /session/status` with bounded exponential backoff until one poll
     * succeeds, then returns so the caller can retry SSE. Server data wins: the
     * statuses are replaced by the polled map.
     */
    private suspend fun pollUntilHealthy() {
        var interval = config.pollInitialIntervalMillis
        while (currentCoroutineContext().isActive) {
            try {
                val polled = transport.pollStatuses()
                mutableState.value = mutableState.value.copy(statuses = polled)
                return
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                delay(interval)
                interval = nextBackoff(interval, config.pollMaxBackoffMillis)
            }
        }
    }

    private fun nextBackoff(current: Long, max: Long): Long =
        minOf(max, maxOf(current + 1L, (current * config.backoffMultiplier).toLong()))
}

/**
 * The D9 guard: runs a user mutation once and returns its outcome.
 *
 * A failed mutation fails. There is no queue, no retry, and no replay anywhere in
 * the realtime layer, so a reconnect can never re-send an approve/deny, prompt, or
 * abort with real side effects.
 */
public class FailFastMutationSender : MutationSender {
    override suspend fun send(mutation: UserMutation): Result<Unit> =
        try {
            Result.success(mutation.execute())
        } catch (cancellation: CancellationException) {
            // Structured concurrency: cancellation is not a mutation failure, it must
            // propagate so the caller's scope actually stops.
            throw cancellation
        } catch (failure: Throwable) {
            Result.failure(failure)
        }
}
