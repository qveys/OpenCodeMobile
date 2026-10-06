package org.opencodemobile.features.sessions

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.opencodemobile.shared.application.session.SessionListController
import org.opencodemobile.shared.application.session.SessionListState
import org.opencodemobile.shared.domain.session.SessionSummary

/**
 * The V1-04 sessions presenter the Compose layer observes.
 *
 * It is intentionally thin: every gate (offline read-only, server surface,
 * cache fallback, error mapping) lives in
 * [org.opencodemobile.shared.application.session.SessionListController]; the
 * presenter only forwards user intents and re-exposes the state flow.
 */
public class SessionsPresenter(
    private val controller: SessionListController,
    private val scope: CoroutineScope,
) {
    public val state: StateFlow<SessionListState> = controller.state

    /** Loads the list (server, or cache offline). */
    public fun refresh(): Unit {
        scope.launch { controller.refresh() }
    }

    /** Creates a session. A null [title] lets the server name it. */
    public fun createSession(title: String? = null): Unit {
        scope.launch { controller.createSession(title) }
    }

    /** Renames [sessionId]. */
    public fun renameSession(sessionId: String, title: String): Unit {
        scope.launch { controller.renameSession(sessionId, title) }
    }

    /** Deletes [sessionId]. */
    public fun deleteSession(sessionId: String): Unit {
        scope.launch { controller.deleteSession(sessionId) }
    }

    /** Forks [sessionId]; refused locally when the server surface has no fork. */
    public fun forkSession(sessionId: String): Unit {
        scope.launch { controller.forkSession(sessionId) }
    }

    /**
     * Resumes [sessionId] and returns the row to open, or null when it cannot
     * be opened. The caller navigates; the presenter never navigates itself.
     */
    public suspend fun openSession(sessionId: String): SessionSummary? =
        controller.openSession(sessionId)

    public fun clearMessage(): Unit = controller.clearMessage()
}
