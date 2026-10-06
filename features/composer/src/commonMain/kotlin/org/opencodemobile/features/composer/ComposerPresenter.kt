package org.opencodemobile.features.composer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.opencodemobile.shared.application.chat.ComposerController
import org.opencodemobile.shared.application.chat.ComposerState

/**
 * The V1-05 composer presenter the Compose layer observes.
 *
 * It is intentionally thin: the draft, the §8.1 "never sent automatically" rule,
 * the D8 offline gate and the D9 single send all live in [ComposerController];
 * the presenter only forwards user intents and re-exposes the state flow.
 */
public class ComposerPresenter(
    private val controller: ComposerController,
    private val scope: CoroutineScope,
) {
    public val state: StateFlow<ComposerState> = controller.state

    /** Opens [sessionId] and restores its draft. Never sends. */
    public fun open(sessionId: String): Unit {
        scope.launch { controller.open(sessionId) }
    }

    /** Records a keystroke; the draft is persisted locally. */
    public fun updateDraft(text: String): Unit {
        scope.launch { controller.updateDraft(text) }
    }

    /** Sends the current draft exactly once. */
    public fun send(): Unit {
        scope.launch { controller.send() }
    }

    public fun close(): Unit = controller.close()
}
