package org.opencodemobile.shared.domain.dictation

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Why on-device dictation cannot run.
 *
 * Every reason is terminal for V1: `docs/adr/on-device-speech-to-text.md` forbids
 * any fallback to a cloud-capable recognizer, so a caller must show the disabled
 * state instead of retrying another way.
 */
public enum class DictationUnavailableReason {
    /** The OS reports no installed on-device model for the active locale/device. */
    OnDeviceModelMissing,

    /** The active language is not supported by the on-device recognizer. */
    LocaleNotSupported,

    /** Microphone / speech-recognition authorization was refused. */
    PermissionDenied,

    /** The running OS version has no on-device recognition entry point. */
    PlatformNotSupported,

    /** No platform implementation is wired; the fail-closed default. */
    NotWired,

    /** Any other platform failure. It is never a signal to retry through the network. */
    Failed,
}

/**
 * The closed availability model of the dictation provider (ADR T5).
 *
 * A caller must branch on this instead of a boolean: [Unavailable] carries the
 * reason the composer shows with a disabled mic button.
 */
public sealed interface DictationAvailability {
    /** On-device recognition is ready for [locale] (a BCP-47 language tag). */
    public data class Available(public val locale: String) : DictationAvailability

    /** On-device recognition cannot run; dictation stays disabled. */
    public data class Unavailable(public val reason: DictationUnavailableReason) : DictationAvailability
}

/**
 * One event of a dictation session.
 *
 * [Partial] is a cumulative transcript of the current session (the platform keeps
 * refining it), so a consumer replaces the session text rather than appending each
 * partial — appending would duplicate words. The session always ends with exactly
 * one of [Final], [Unavailable] or [Stopped].
 */
public sealed interface DictationEvent {
    /** The current cumulative transcript. */
    public data class Partial(public val text: String) : DictationEvent

    /** The recognizer finalized [text] and the session is over. */
    public data class Final(public val text: String) : DictationEvent

    /** The session failed closed with [reason]; dictation must not retry. */
    public data class Unavailable(public val reason: DictationUnavailableReason) : DictationEvent

    /** The session ended without a final result (cancelled). */
    public data object Stopped : DictationEvent
}

/**
 * On-device speech-to-text port (T5, ADR `docs/adr/on-device-speech-to-text.md`).
 *
 * V1 ships exactly one implementation per platform
 * (`AndroidOnDeviceDictationProvider` / `IosOnDeviceDictationProvider`), each
 * restricted to the platform entry point that is contractually on-device-only:
 *
 * - Android: `SpeechRecognizer.createOnDeviceSpeechRecognizer(Context)`, never
 *   `createSpeechRecognizer(Context)` and never the system dictation intent.
 * - iOS: `SFSpeechRecognizer(locale:)` gated on `supportsOnDeviceRecognition`,
 *   with `SFSpeechAudioBufferRecognitionRequest.requiresOnDeviceRecognition = true`.
 *
 * The interface lives in the domain (like [org.opencodemobile.shared.domain.permission.BiometricAuthenticator])
 * so the application layer can depend on it and post-V1 providers (for example a
 * self-hosted server transcription over the existing TLS tunnel) can be added
 * without touching the composer. The flow emits transcript text only; it has no
 * send capability. The composer alone decides when to send, on a distinct user tap.
 */
public interface DictationProvider {
    /**
     * Checks on-device capability for [locale] (a BCP-47 language tag). Must be
     * re-checked whenever the active locale changes: on-device model coverage
     * differs per language/device/OEM.
     *
     * Implementations must be safe to call repeatedly and must never attempt a
     * network probe.
     */
    public suspend fun availability(locale: String): DictationAvailability

    /**
     * Starts an on-device recognition session for [locale] and emits transcript
     * events. Collecting the flow starts the session; cancelling the collection
     * stops it. Transcription never sends anything: it only produces text.
     */
    public fun transcribe(locale: String): Flow<DictationEvent>

    /** Stops any active session. Safe to call when none is active. */
    public suspend fun cancel()
}

/**
 * Fail-closed default used when no platform implementation is wired.
 *
 * It reports [DictationUnavailableReason.NotWired] for every locale and never
 * transcribes, so a missing binding disables the mic button instead of silently
 * reaching a network-capable recognizer.
 */
public object FailClosedDictationProvider : DictationProvider {
    override suspend fun availability(locale: String): DictationAvailability =
        DictationAvailability.Unavailable(DictationUnavailableReason.NotWired)

    override fun transcribe(locale: String): Flow<DictationEvent> =
        flowOf(DictationEvent.Unavailable(DictationUnavailableReason.NotWired))

    override suspend fun cancel(): Unit = Unit
}
