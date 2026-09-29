package org.opencodemobile.features.transcript

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.opencodemobile.shared.application.chat.TranscriptController
import org.opencodemobile.shared.application.chat.TranscriptState

/**
 * The V1-05 transcript presenter the Compose layer observes.
 *
 * All reconciliation (server transcript, live upsert by part id, reconnect
 * re-fetch) lives in [TranscriptController]; the presenter only opens/closes the
 * session and re-exposes the state flow.
 */
public class TranscriptPresenter(
    private val controller: TranscriptController,
    private val scope: CoroutineScope,
) {
    public val state: StateFlow<TranscriptState> = controller.state

    /** Opens [sessionId] and reconciles its transcript from the server. */
    public fun open(sessionId: String): Unit {
        scope.launch { controller.open(sessionId) }
    }

    /** Re-reads the authoritative transcript. */
    public fun refresh(): Unit {
        scope.launch { controller.refresh() }
    }

    public fun close(): Unit = controller.close()
}
