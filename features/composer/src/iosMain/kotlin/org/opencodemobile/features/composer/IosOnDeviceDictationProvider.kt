package org.opencodemobile.features.composer

import kotlin.coroutines.resume
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.opencodemobile.shared.domain.dictation.DictationAvailability
import org.opencodemobile.shared.domain.dictation.DictationEvent
import org.opencodemobile.shared.domain.dictation.DictationProvider
import org.opencodemobile.shared.domain.dictation.DictationUnavailableReason
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioSession
import platform.Foundation.NSLocale
import platform.Speech.SFSpeechAudioBufferRecognitionRequest
import platform.Speech.SFSpeechRecognizer
import platform.Speech.SFSpeechRecognizerAuthorizationStatus

/**
 * iOS on-device [DictationProvider] (T5,
 * `docs/adr/on-device-speech-to-text.md`).
 *
 * Enforcement is two-layered and both layers are required:
 *
 * 1. [SFSpeechRecognizer.supportsOnDeviceRecognition] is checked for the active
 *    locale before a session starts; `false` disables dictation.
 * 2. Every request sets
 *    [SFSpeechAudioBufferRecognitionRequest.requiresOnDeviceRecognition] to
 *    `true`, which turns "may be on-device" into "on-device or fail". Without
 *    this flag iOS is permitted to use server-based recognition at its
 *    discretion, so it is the actual enforcement point.
 *
 * `architecture-tests` asserts both the flag and the capability gate stay
 * present. A recognition failure is mapped to the unavailable state; it never
 * retries without the flag. The microphone and speech authorizations are
 * requested at first use, never at app startup.
 */
@OptIn(ExperimentalForeignApi::class)
public class IosOnDeviceDictationProvider : DictationProvider {

    override suspend fun availability(locale: String): DictationAvailability {
        val recognizer = recognizer(locale)
            ?: return DictationAvailability.Unavailable(DictationUnavailableReason.LocaleNotSupported)
        if (!recognizer.supportsOnDeviceRecognition) {
            return DictationAvailability.Unavailable(DictationUnavailableReason.OnDeviceModelMissing)
        }
        return DictationAvailability.Available(locale)
    }

    @Suppress("LongMethod")
    override fun transcribe(locale: String): Flow<DictationEvent> = callbackFlow {
        val recognizer = recognizer(locale)
        if (recognizer == null || !recognizer.supportsOnDeviceRecognition) {
            trySend(DictationEvent.Unavailable(DictationUnavailableReason.OnDeviceModelMissing))
            close()
            return@callbackFlow
        }
        if (!requestAuthorizations()) {
            trySend(DictationEvent.Unavailable(DictationUnavailableReason.PermissionDenied))
            close()
            return@callbackFlow
        }

        val request = SFSpeechAudioBufferRecognitionRequest().apply {
            // The enforcement point: on-device or fail, never a server fallback.
            requiresOnDeviceRecognition = true
            shouldReportPartialResults = true
        }
        val audioEngine = AVAudioEngine()
        val inputNode = audioEngine.inputNode

        val task = recognizer.recognitionTaskWithRequest(request) { result, error ->
            if (error != null) {
                trySend(DictationEvent.Unavailable(DictationUnavailableReason.Failed))
                close()
                return@recognitionTaskWithRequest
            }
            val text = result?.bestTranscription?.formattedString.orEmpty()
            if (result?.`final` == true) {
                trySend(DictationEvent.Final(text))
                close()
            } else {
                trySend(DictationEvent.Partial(text))
            }
        }

        inputNode.installTapOnBus(
            bus = 0u,
            bufferSize = 1024u,
            format = inputNode.outputFormatForBus(0u),
        ) { buffer, _ ->
            if (buffer != null) request.appendAudioPCMBuffer(buffer)
        }
        audioEngine.prepare()
        audioEngine.startAndReturnError(null)

        awaitClose {
            inputNode.removeTapOnBus(0u)
            audioEngine.stop()
            request.endAudio()
            task.cancel()
        }
    }

    override suspend fun cancel(): Unit = Unit

    private fun recognizer(locale: String): SFSpeechRecognizer? =
        SFSpeechRecognizer(locale = NSLocale(localeIdentifier = locale))

    private suspend fun requestAuthorizations(): Boolean {
        val speech = suspendCancellableCoroutine { continuation ->
            SFSpeechRecognizer.requestAuthorization { status ->
                continuation.resume(
                    status == SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusAuthorized,
                )
            }
        }
        if (!speech) return false
        return suspendCancellableCoroutine { continuation ->
            AVAudioSession.sharedInstance().requestRecordPermission { granted ->
                continuation.resume(granted)
            }
        }
    }
}
