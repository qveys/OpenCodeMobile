package org.opencodemobile.shared.application.permission

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.opencodemobile.shared.domain.event.ConnectionPhase
import org.opencodemobile.shared.domain.event.EventSource
import org.opencodemobile.shared.domain.permission.PermissionEventDecoder

/**
 * Bridges the realtime pipeline to the permission coordinator (V1-06).
 *
 * This is the consumer the security review asked for:
 * - every normalized SSE event is decoded by [decoder]; `permission.asked` /
 *   `permission.replied` reach the coordinator, so pending requests surface as
 *   they arrive (not only on the `GET /permission` reconcile), and
 * - every transition into [ConnectionPhase.Live] triggers a reconcile, so a
 *   request decided elsewhere while the app was killed or offline disappears.
 *
 * It never sends a decision: approval/deny stay on [PermissionCoordinator].
 */
public class PermissionRealtimeBridge(
    private val source: EventSource,
    private val decoder: PermissionEventDecoder,
    private val coordinator: PermissionCoordinator,
) {
    /** Starts both collectors in [scope] and returns their job. Idempotent per caller. */
    public fun start(scope: CoroutineScope): Job = scope.launch {
        launch {
            source.events.collect { event ->
                // The decoder contract is "never throws", but it is a third-party
                // seam: an uncaught throw would permanently cancel this collector
                // inside a SupervisorJob scope, silently stopping all permission
                // events for the process (N7). Enforce the contract at the boundary.
                val decoded = runCatching { decoder.decode(event.type, event.payload) }.getOrNull()
                decoded?.let { coordinator.onEvent(it) }
            }
        }
        launch {
            source.state
                .map { it.phase }
                .distinctUntilChanged()
                .collect { phase ->
                    if (phase == ConnectionPhase.Live) coordinator.reconcile()
                }
        }
    }
}
