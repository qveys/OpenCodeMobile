package org.opencodemobile.android.questions

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.opencodemobile.shared.application.interaction.PendingQuestionsController
import org.opencodemobile.shared.application.interaction.PendingQuestionsRealtimeBridge

/**
 * Starts the V1-07 pending-question surface (OPE-192).
 *
 * The bridge it starts does two things, and both are required by the recette:
 * it refreshes `GET /question` once when it starts (the process may have been
 * killed with a question still open) and again on every return to `Live`. It
 * answers nothing: replies and rejects stay explicit user actions on the
 * presenter.
 *
 * It mirrors [org.opencodemobile.android.permission.PermissionRuntime]: the app
 * shell has no connection composition root yet, so [resolveBridge] is resolved at
 * `start` time. The connection root should call [start] again once it has bound a
 * [QuestionConnection]; the first non-null resolution starts the bridge, and
 * further calls are no-ops.
 */
public class PendingQuestionsRuntime(
    private val controller: PendingQuestionsController?,
    private val resolveBridge: () -> PendingQuestionsRealtimeBridge? = { null },
) {
    private var bridgeJob: Job? = null

    /** No-op while no connection is wired; safe to call more than once. */
    public fun start(scope: CoroutineScope) {
        controller ?: return
        if (bridgeJob == null) {
            bridgeJob = resolveBridge()?.start(scope)
        }
    }
}
