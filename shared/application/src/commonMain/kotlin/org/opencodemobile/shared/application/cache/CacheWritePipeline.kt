package org.opencodemobile.shared.application.cache

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.opencodemobile.shared.domain.cache.ConnectionState
import org.opencodemobile.shared.domain.cache.ConnectivityMutationGate
import org.opencodemobile.shared.domain.cache.SessionCacheWriter
import org.opencodemobile.shared.domain.event.ConnectionPhase
import org.opencodemobile.shared.domain.event.EventSource

/**
 * The production cache write path (OPE-180 / D8).
 *
 * It is the single point that connects a live [EventSource] to the local cache:
 *
 * - every observed [ConnectionPhase] is mirrored onto the cache's
 *   [ConnectivityMutationGate] through
 *   [ConnectivityMutationGate.onConnectionStateChanged], so the gate follows the
 *   connection instead of staying at its `Offline` default, and
 * - the [RealtimeCacheProjector] projects server snapshots and events through the
 *   already gated [SessionCacheWriter] — the one `CacheStack.writer()` hands out.
 *
 * The gate update and the projection of one state run in the same collector, so a
 * snapshot can never race its own gate update: while the pipeline reports a live
 * connection the snapshot is accepted, and an offline state refuses it without
 * queueing anything (the cache is rebuilt from the next snapshot).
 *
 * Composition roots (`androidApp` and the iOS host) build one instance per
 * connection and call [start] with the process scope.
 */
public class CacheWritePipeline(
    source: EventSource,
    writer: SessionCacheWriter,
    private val gate: ConnectivityMutationGate,
    scope: CacheScope,
) {
    private val source: EventSource = source
    private val projector: RealtimeCacheProjector = RealtimeCacheProjector(source, writer, scope)

    /** Starts the connectivity binding and the projector; returns the collector job. */
    public fun start(coroutineScope: CoroutineScope): Job = coroutineScope.launch {
        launch {
            source.state.collect { state ->
                gate.onConnectionStateChanged(state.phase.toConnectionState())
                projector.onState(state)
            }
        }
        launch { source.events.collect(projector::onEvent) }
    }
}

/**
 * Maps the realtime [ConnectionPhase] onto the D8 [ConnectionState].
 *
 * [ConnectionPhase.Reconciling] and [ConnectionPhase.Live] are the phases in
 * which the pipeline is demonstrably talking to the server (the snapshot is being
 * fetched, or the SSE stream is open), so the cache may be written. The SSE being
 * down ([ConnectionPhase.Polling]), the pipeline never having started
 * ([ConnectionPhase.Idle]) and the pipeline being stopped
 * ([ConnectionPhase.Stopped]) are all offline for the D8 policy: the cache is
 * read-only and nothing is queued.
 *
 * `Polling` is deliberately treated as offline even though the HTTP fallback can
 * still reach the server. The projector never writes from a poll, so nothing is
 * lost, and staying fail-close can only refuse a write, never permit one while
 * the event stream is down.
 */
public fun ConnectionPhase.toConnectionState(): ConnectionState = when (this) {
    ConnectionPhase.Reconciling,
    ConnectionPhase.Live,
    -> ConnectionState.Online

    ConnectionPhase.Idle,
    ConnectionPhase.Polling,
    ConnectionPhase.Stopped,
    -> ConnectionState.Offline
}
