package org.opencodemobile.features.composer

import kotlinx.coroutines.flow.StateFlow
import org.opencodemobile.shared.application.composer.ComposerController
import org.opencodemobile.shared.application.composer.ComposerState

/**
 * The V1-10 composer presenter the Compose layer observes.
 *
 * It is intentionally thin: the draft, the dictation session, the availability
 * re-check and the explicit send all live in
 * [org.opencodemobile.shared.application.composer.ComposerController]; the
 * presenter only forwards user intents and re-exposes the state flow.
 */
public class DictationComposerPresenter(
    private val controller: ComposerController,
) {
    /** The state the composer screen renders. */
    public val state: StateFlow<ComposerState> = controller.state

    /** Loads the persisted draft and checks capability for [localeTag] once. */
    public fun start(localeTag: String) {
        controller.start(localeTag)
    }

    /** Re-checks on-device capability when the active locale changes (FR/EN). */
    public fun onLocaleChanged(localeTag: String) {
        controller.onLocaleChanged(localeTag)
    }

    /** Records typed text in the draft. */
    public fun onDraftChange(text: String) {
        controller.onDraftChange(text)
    }

    /** Starts or stops the on-device dictation session. */
    public fun toggleDictation() {
        controller.toggleDictation()
    }

    /** Stops the on-device dictation session. */
    public fun stopDictation() {
        controller.stopDictation()
    }

    /** Sends the draft; call this only from a distinct user tap. */
    public fun send() {
        controller.send()
    }

    /** Dismisses the last notice. */
    public fun dismissNotice() {
        controller.dismissNotice()
    }
}
