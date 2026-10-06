package org.opencodemobile.shared.application.composer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.opencodemobile.shared.domain.chat.ChatPrompt
import org.opencodemobile.shared.domain.chat.ComposerDraftStore
import org.opencodemobile.shared.domain.chat.OpenCodeChatGateway
import org.opencodemobile.shared.domain.dictation.DictationAvailability
import org.opencodemobile.shared.domain.dictation.DictationEvent
import org.opencodemobile.shared.domain.dictation.DictationProvider
import org.opencodemobile.shared.domain.dictation.DictationUnavailableReason

/**
 * The V1-10 composer controller: owns the prompt draft, the dictation session,
 * and the explicit send action.
 *
 * Security invariants (T5 / ADR `docs/adr/on-device-speech-to-text.md`):
 *
 * - Dictation writes into [ComposerState.draft] **only**. Nothing in the
 *   transcription path can reach [OpenCodeChatGateway.sendPrompt]; a prompt is
 *   sent only when the user taps send, which calls [send] explicitly. This is
 *   enforced structurally: the dictation collector never references the gateway.
 * - Availability is re-checked whenever the locale changes (FR/EN in V1), so a
 *   language the device has no on-device model for disables the mic button.
 * - A dictation failure ([DictationEvent.Unavailable]) moves the state to the
 *   disabled availability; it never retries through another recognizer.
 *
 * Draft persistence is optional ([drafts]): when present, the draft is loaded on
 * [start] and cleared locally after a successful send. Restoring a draft never
 * sends it (§8.1).
 */
public class ComposerController(
    private val chat: OpenCodeChatGateway,
    private val dictation: DictationProvider,
    private val drafts: ComposerDraftStore?,
    private val sessionId: String,
    private val scope: CoroutineScope,
    initialLocale: String = "",
) {
    private val _state = MutableStateFlow(ComposerState(sessionId = sessionId, locale = initialLocale))

    /** The state the composer screen observes. */
    public val state: StateFlow<ComposerState> = _state.asStateFlow()

    private var transcription: Job? = null

    /** The draft captured before the current dictation session; partials replace from here. */
    private var dictationBase: String = ""

    /**
     * Loads the persisted draft and checks dictation capability for [locale].
     * Call once when the composer appears.
     */
    public fun start(locale: String) {
        onLocaleChanged(locale)
        scope.launch {
            val stored = drafts?.loadDraft(sessionId).orEmpty()
            // Never overwrite text the user already started typing while loading.
            _state.update { current ->
                if (stored.isNotEmpty() && current.draft.isEmpty()) current.copy(draft = stored) else current
            }
        }
    }

    /**
     * Re-checks on-device capability for [locale] (criterion 6: FR/EN switch) and
     * stops any active session, so a stale locale never keeps the mic enabled.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    public fun onLocaleChanged(locale: String) {
        _state.update { it.copy(locale = locale) }
        if (_state.value.dictating) stopDictation()
        scope.launch {
            val availability = try {
                dictation.availability(locale)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                DictationAvailability.Unavailable(DictationUnavailableReason.Failed)
            }
            // Drop a result whose locale is no longer active (slow platform query).
            _state.update { current ->
                if (current.locale == locale) current.copy(dictation = availability) else current
            }
        }
    }

    /** Persists [text] as the draft. Called as the user types or dictates. */
    public fun onDraftChange(text: String) {
        _state.update { it.copy(draft = text, notice = null) }
        scope.launch { drafts?.saveDraft(sessionId, text) }
    }

    /** Toggles the microphone: starts, or stops, an on-device session. */
    public fun toggleDictation() {
        if (_state.value.dictating) stopDictation() else startDictation()
    }

    /** Starts dictation when on-device recognition is available; otherwise no-op. */
    public fun startDictation() {
        val current = _state.value
        if (current.dictating || current.dictation !is DictationAvailability.Available) return
        dictationBase = current.draft
        _state.update { it.copy(dictating = true, notice = null) }
        transcription = scope.launch {
            try {
                dictation.transcribe(current.locale).collect(::applyDictationEvent)
            } finally {
                _state.update { it.copy(dictating = false) }
            }
        }
    }

    /** Stops the active session; safe to call when none is active. */
    public fun stopDictation() {
        transcription?.cancel()
        transcription = null
        _state.update { it.copy(dictating = false) }
        scope.launch { dictation.cancel() }
    }

    /**
     * Sends the trimmed draft. This is the only method that can reach the chat
     * gateway, and it is called only from a distinct user tap.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    public fun send() {
        val current = _state.value
        val text = current.draft.trim()
        if (text.isEmpty() || current.sending || current.dictating) return
        _state.update { it.copy(sending = true, notice = null) }
        scope.launch {
            try {
                chat.sendPrompt(sessionId, ChatPrompt(text = text))
                drafts?.saveDraft(sessionId, "")
                _state.update { it.copy(draft = "", sending = false) }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                // Keep the draft so the user can retry; a failed send is never retried here.
                _state.update { it.copy(sending = false, notice = ComposerNotice.SendFailed) }
            }
        }
    }

    /** Clears the last notice. */
    public fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    private fun applyDictationEvent(event: DictationEvent) {
        when (event) {
            is DictationEvent.Partial -> setDraftFromDictation(event.text)
            is DictationEvent.Final -> setDraftFromDictation(event.text)
            is DictationEvent.Unavailable ->
                _state.update { it.copy(dictation = DictationAvailability.Unavailable(event.reason)) }
            DictationEvent.Stopped -> Unit
        }
    }

    /** Writes the cumulative session transcript into the draft. */
    private fun setDraftFromDictation(spoken: String) {
        val composed = composeDraft(dictationBase, spoken)
        _state.update { it.copy(draft = composed) }
    }
}

/**
 * Joins the pre-session draft with the current session transcript. The platform
 * keeps refining partials, so the caller replaces the session text with each
 * event instead of appending to it; this is what keeps words from duplicating.
 */
private fun composeDraft(base: String, spoken: String): String = when {
    spoken.isBlank() -> base
    base.isBlank() -> spoken
    else -> base.trimEnd() + " " + spoken
}
