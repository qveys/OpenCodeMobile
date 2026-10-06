package org.opencodemobile.features.composer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.opencodemobile.shared.domain.dictation.DictationAvailability
import org.opencodemobile.shared.domain.dictation.DictationEvent
import org.opencodemobile.shared.domain.dictation.DictationProvider
import org.opencodemobile.shared.domain.dictation.DictationUnavailableReason

/**
 * Android on-device [DictationProvider] (T5,
 * `docs/adr/on-device-speech-to-text.md`).
 *
 * The **only** recognizer factory used is
 * `SpeechRecognizer.createOnDeviceSpeechRecognizer`, available from API 31 (the
 * project floor). The network-capable `createSpeechRecognizer` factory and the
 * system dictation intent (`ACTION_RECOGNIZE_SPEECH` launched through
 * `startActivity`) are deliberately absent: `architecture-tests` fails the build
 * if either reappears anywhere in the V1 sources.
 *
 * Capability is a live runtime query, not an API-level assumption: on API 33+
 * [SpeechRecognizer.isOnDeviceRecognitionAvailable] gates the session; on API
 * 31–32 no query exists, so a missing model surfaces through
 * [RecognitionListener.onError] as [DictationUnavailableReason.LocaleNotSupported]
 * and dictation stays off. A recognition error never falls back to the network.
 *
 * It must be constructed from `onCreate`, before the activity reaches the
 * `STARTED` state, because it registers an `ActivityResultLauncher` for the
 * microphone permission — requested at first use, never at app startup.
 */
public class AndroidOnDeviceDictationProvider(
    private val activity: ComponentActivity,
) : DictationProvider {

    private var permissionContinuation: CancellableContinuation<Boolean>? = null

    private val permissionLauncher =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val continuation = permissionContinuation ?: return@registerForActivityResult
            permissionContinuation = null
            if (continuation.isActive) continuation.resume(granted)
        }

    override suspend fun availability(locale: String): DictationAvailability {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !SpeechRecognizer.isOnDeviceRecognitionAvailable(activity)
        ) {
            return DictationAvailability.Unavailable(DictationUnavailableReason.OnDeviceModelMissing)
        }
        return DictationAvailability.Available(locale)
    }

    @Suppress("LongMethod")
    override fun transcribe(locale: String): Flow<DictationEvent> = callbackFlow {
        if (!ensureRecordPermission()) {
            trySend(DictationEvent.Unavailable(DictationUnavailableReason.PermissionDenied))
            close()
            return@callbackFlow
        }

        val listener = object : RecognitionListener {
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.let { trySend(DictationEvent.Partial(it)) }
            }

            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                trySend(DictationEvent.Final(text))
                close()
            }

            override fun onError(error: Int) {
                trySend(DictationEvent.Unavailable(mapError(error)))
                close()
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit

            override fun onBeginningOfSpeech() = Unit

            override fun onRmsChanged(rmsdB: Float) = Unit

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() = Unit

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        }

        // The action is only the container for the language fine-tuning extras
        // that startListening requires; it is never launched as an activity.
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        val recognizer = withContext(Dispatchers.Main.immediate) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(activity).also {
                it.setRecognitionListener(listener)
                it.startListening(intent)
            }
        }

        awaitClose {
            activity.runOnUiThread { recognizer.destroy() }
        }
    }

    override suspend fun cancel(): Unit = withContext(Dispatchers.Main.immediate) {
        // Cancelling the flow collection runs the callbackFlow's awaitClose,
        // which destroys that session's recognizer; there is no shared instance.
    }

    private suspend fun ensureRecordPermission(): Boolean {
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return true
        }
        return suspendCancellableCoroutine { continuation ->
            permissionContinuation?.cancel()
            permissionContinuation = continuation
            continuation.invokeOnCancellation {
                if (permissionContinuation === continuation) permissionContinuation = null
            }
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun mapError(error: Int): DictationUnavailableReason = when (error) {
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
        -> DictationUnavailableReason.LocaleNotSupported

        else -> DictationUnavailableReason.Failed
    }
}
