package org.opencodemobile.shared.application.interaction

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.opencodemobile.shared.domain.event.ConnectionPhase
import org.opencodemobile.shared.domain.event.EventSource

/**
 * Keeps the pending-question surface in step with the connection (V1-07).
 *
 * A question is server state, so the client never persists it: it **re-reads**
 * `GET /question` instead of trusting a local copy. This bridge is the wiring
 * the recette found missing:
 *
 * - it refreshes once on [start] (the "app start" read), so a killed-and-relaunched
 *   process shows whatever the server still holds, and
 * - it refreshes on **every** transition into [ConnectionPhase.Live], so a
 *   question asked while the app was offline, killed, or on another screen
 *   surfaces as soon as the connection is re-established.
 *
 * It never answers or rejects anything: those stay explicit user actions on
 * [PendingQuestionsController].
 *
 * [directory] scopes the read to the active project root (ADR-0002 §3.3), exactly
 * like the gateway contract.
 */
public class PendingQuestionsRealtimeBridge(
    private val source: EventSource,
    private val controller: PendingQuestionsController,
    private val directory: String? = null,
) {
    /**
     * Starts the connection-driven refreshes in [scope] and returns their job.
     *
     * A second call starts a second collector; callers own the job and cancel it
     * when the surface goes away.
     */
    public fun start(scope: CoroutineScope): Job = scope.launch {
        // "Refresh at start": the process may have been killed with a question
        // still open, so the first thing the surface does is ask the server.
        controller.refresh(directory)
        launch {
            source.state
                .map { it.phase }
                .distinctUntilChanged()
                .collect { phase ->
                    if (phase == ConnectionPhase.Live) controller.refresh(directory)
                }
        }
    }
}
