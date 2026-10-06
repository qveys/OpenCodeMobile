package org.opencodemobile.shared.application.notification

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.opencodemobile.shared.domain.event.EventSource

/**
 * Feeds the reconciled realtime model (V1-12 §3.2) into the local-notification
 * coordinator, so a finished or failed turn is surfaced (V1-13).
 *
 * It observes only; it never mutates the pipeline and never replays anything. The
 * statuses come from the server snapshot and the polling fallback, so the
 * notification is a projection of server state and losing it costs nothing.
 */
public class SessionNotificationRealtimeBridge(
    private val source: EventSource,
    private val coordinator: LocalNotificationCoordinator,
) {
    /** Starts the observation in [scope]; callers own the returned job. */
    public fun start(scope: CoroutineScope): Job = scope.launch {
        source.state
            .map { state -> state.sessions to state.statuses }
            .distinctUntilChanged()
            .collect { (sessions, statuses) -> coordinator.onSessionStatuses(sessions, statuses) }
    }
}
