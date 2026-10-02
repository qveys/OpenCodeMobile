package org.opencodemobile.shared.domain.event

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The port through which the rest of the app observes a live OpenCode connection.
 *
 * There is exactly one implementation per connection (`EventProcessor` in
 * `shared/realtime`); features depend on this interface, never on the concrete
 * pipeline. It exposes two read-only surfaces:
 *
 * - [state], a hot [StateFlow] of the reconciled model (snapshot + statuses), and
 * - [events], a hot [SharedFlow] of normalized, ordered, deduplicated events.
 *
 * The port never exposes a mutation path: user actions (prompt, approve, abort) go
 * through the gateway use cases, and D9 forbids queueing or replaying them here.
 */
public interface EventSource {
    /** The reconciled model. Starts at [RealtimeState] with [ConnectionPhase.Idle]. */
    public val state: StateFlow<RealtimeState>

    /** Normalized events, emitted in server order with duplicates dropped. */
    public val events: SharedFlow<ServerEvent>

    /**
     * Starts the pipeline in [scope]. Idempotent while already active: a second call
     * before [stop] is a no-op.
     */
    public fun start(scope: CoroutineScope)

    /** Stops the pipeline and moves the state to [ConnectionPhase.Stopped]. Idempotent. */
    public fun stop()
}

/**
 * The transport port the realtime pipeline drives.
 *
 * It is implemented by `shared/networking` (the only module allowed to touch the
 * generated OpenCode client and Ktor) and faked in tests. Keeping it in
 * `shared/domain` is what lets `EventProcessor` depend on the domain only.
 *
 * Responsibilities are deliberately split:
 * - [fetchSnapshot] is the full (re)connect snapshot: sessions + statuses.
 * - [openEventStream] is the SSE stream (`GET /event`); it emits raw records and
 *   completes (or throws) when the stream ends prematurely.
 * - [pollStatuses] is the mandatory polling fallback (`GET /session/status`).
 */
public interface EventTransport {
    /**
     * Fetches the reconciliation snapshot. Throws on any failure; the pipeline
     * treats a failed snapshot as a transport failure and backs off.
     */
    public suspend fun fetchSnapshot(): RealtimeSnapshot

    /**
     * Reads the polling fallback (`GET /session/status`). Throws on any failure; the
     * pipeline treats a failed poll as the transport still being unhealthy.
     */
    public suspend fun pollStatuses(): Map<String, SessionStatus>

    /**
     * Opens the SSE stream. The returned flow emits records as the body is read
     * progressively and completes when the server closes the body. A transport-level
     * failure surfaces as an exception on the flow.
     *
     * The flow must be cold per collection so a reconnect opens a fresh connection.
     */
    public fun openEventStream(): Flow<RawServerEvent>
}

/**
 * A user-initiated mutation (send prompt, approve/deny, answer, abort).
 *
 * This port exists only so the realtime layer can state the D9 invariant in code:
 * a mutation is executed exactly once, on the caller's coroutine. It is never
 * enqueued, retried, or replayed across a reconnect — replaying a mutation that has
 * real side effects on the user's machine is forbidden (T2/T6).
 */
public fun interface UserMutation {
    public suspend fun execute()
}

/**
 * Executes a [UserMutation] exactly once and surfaces the outcome.
 *
 * A failed mutation fails: the caller receives a failed [Result] and nothing is
 * queued for a later transport recovery. There is no retry hook and no replay list.
 */
public interface MutationSender {
    /** Runs [mutation] once. Never queues, retries, or replays it (D9). */
    public suspend fun send(mutation: UserMutation): Result<Unit>
}
