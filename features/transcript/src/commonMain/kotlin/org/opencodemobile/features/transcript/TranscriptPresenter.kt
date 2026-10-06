package org.opencodemobile.features.transcript

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.opencodemobile.shared.application.chat.TranscriptController
import org.opencodemobile.shared.application.chat.TranscriptState
import org.opencodemobile.shared.application.interaction.TurnAbortController
import org.opencodemobile.shared.application.interaction.TurnAbortState

/**
 * The V1-05 transcript presenter the Compose layer observes.
 *
 * All reconciliation (server transcript, live upsert by part id, reconnect
 * re-fetch) lives in [TranscriptController]; the presenter only opens/closes the
 * session and re-exposes the state flow.
 *
 * It also exposes the V1-08 abort surface: [abort] is the single explicit user
 * action, and [abortState] is what the screen renders (in-flight, awaiting the
 * snapshot that wins after an abort, or the last error). The gates (D8 offline,
 * "exactly once", the resync rule) live in [TurnAbortController].
 */
public class TranscriptPresenter(
    private val controller: TranscriptController,
    private val scope: CoroutineScope,
    private val turnAbort: TurnAbortController? = null,
) {
    public val state: StateFlow<TranscriptState> = controller.state

    private val idleAbortState = MutableStateFlow(TurnAbortState())

    /** The abort surface the screen renders; an always-idle state without one. */
    public val abortState: StateFlow<TurnAbortState> = turnAbort?.state ?: idleAbortState

    /** Opens [sessionId] and reconciles its transcript from the server. */
    public fun open(sessionId: String): Unit {
        scope.launch { controller.open(sessionId) }
    }

    /** Re-reads the authoritative transcript. */
    public fun refresh(): Unit {
        scope.launch { controller.refresh() }
    }

    /**
     * Aborts the open turn explicitly (V1-08). No-op until a session is open and
     * an abort surface is wired; the server-side resync is then driven by the
     * realtime bridge. [directory] is the active project root.
     */
    public fun abort(directory: String? = null) {
        val abort = turnAbort ?: return
        val sessionId = controller.state.value.sessionId ?: return
        scope.launch { abort.abort(sessionId, directory) }
    }

    public fun close(): Unit = controller.close()
}
