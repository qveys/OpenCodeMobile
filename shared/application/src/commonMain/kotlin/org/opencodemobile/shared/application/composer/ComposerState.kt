package org.opencodemobile.shared.application.composer

import org.opencodemobile.shared.domain.dictation.DictationAvailability
import org.opencodemobile.shared.domain.dictation.DictationUnavailableReason

/**
 * A short, non-fatal message the composer renders under its controls.
 *
 * Dictation unavailability is not modelled here: it is a first-class
 * [ComposerState.dictation] value, because the mic button is disabled from it.
 */
public enum class ComposerNotice {
    /** The last send failed; the draft is kept so the user can retry. */
    SendFailed,
}

/**
 * Observable state of the prompt composer (V1-10 dictation).
 *
 * [draft] is the single text buffer the composer owns. Dictation only writes
 * [draft]; it never sends. Sending happens solely through an explicit user tap
 * that calls [ComposerController.send].
 */
public data class ComposerState(
    public val sessionId: String,
    public val locale: String,
    public val draft: String = "",
    public val dictation: DictationAvailability =
        DictationAvailability.Unavailable(DictationUnavailableReason.NotWired),
    public val dictating: Boolean = false,
    public val sending: Boolean = false,
    public val notice: ComposerNotice? = null,
) {
    /** The user may send the trimmed [draft] on a distinct tap. */
    public val canSend: Boolean
        get() = draft.isNotBlank() && !sending && !dictating

    /** The mic button is enabled only when on-device dictation is available. */
    public val canDictate: Boolean
        get() = dictation is DictationAvailability.Available && !sending
}
