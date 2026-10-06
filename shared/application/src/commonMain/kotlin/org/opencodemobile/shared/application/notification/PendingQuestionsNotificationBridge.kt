package org.opencodemobile.shared.application.notification

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.opencodemobile.shared.application.interaction.PendingQuestionsState

/**
 * Mirrors the pending-question surface (V1-07) into the local-notification
 * surface (V1-13).
 *
 * A question is server state: the bridge only observes it and posts an
 * informational notification per pending question. It never answers or rejects
 * anything, and an empty list cancels the stale notifications. Because the
 * question list is rebuilt from `GET /question` on every start and reconnect,
 * dropping a notification loses nothing.
 */
public class PendingQuestionsNotificationBridge(
    private val source: StateFlow<PendingQuestionsState>,
    private val coordinator: LocalNotificationCoordinator,
) {
    /** Starts the observation in [scope]; callers own the returned job. */
    public fun start(scope: CoroutineScope): Job = scope.launch {
        source
            .map { it.questions }
            .distinctUntilChanged()
            .collect { coordinator.onPendingQuestions(it) }
    }
}
