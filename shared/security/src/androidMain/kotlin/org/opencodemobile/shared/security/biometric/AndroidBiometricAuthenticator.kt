@file:Suppress("TooGenericExceptionCaught")

package org.opencodemobile.shared.security.biometric

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import org.opencodemobile.shared.domain.permission.BiometricAuthenticator
import org.opencodemobile.shared.domain.permission.BiometricResult

/**
 * Android `actual` of the [BiometricAuthenticator] port (§4 D4 / §7.3).
 *
 * It uses the platform `android.hardware.biometrics.BiometricPrompt` (API 28+,
 * `minSdk` is 31) with a `BIOMETRIC_WEAK | DEVICE_CREDENTIAL` policy, so both an
 * enrolled fingerprint/face and the device credential are accepted — the same
 * fallback the port documents.
 *
 * Fail-closed is the whole point:
 * - [BiometricManager.canAuthenticate] is checked first; anything other than
 *   `BIOMETRIC_SUCCESS` returns [BiometricResult.Unavailable] and no prompt is
 *   shown. No authenticator, or a not-wired implementation, is never success.
 * - A prompt cancelled by the user or the system returns [BiometricResult.Cancelled].
 * - Any other outcome, and any framework failure building/showing the prompt,
 *   returns a non-success result.
 *
 * The caller must use a `Context` whose window is foregrounded; the system
 * dialog is presented by the OS, so an application context is enough. The
 * permission `android.permission.USE_BIOMETRIC` is declared by this module's
 * manifest.
 *
 * @param context any context; the application context is retained.
 */
public class AndroidBiometricAuthenticator(
    context: Context,
) : BiometricAuthenticator {

    private val appContext: Context = context.applicationContext

    override suspend fun authenticate(reason: String): BiometricResult {
        val allowedAuthenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

        val manager = appContext.getSystemService(BiometricManager::class.java)
            ?: return BiometricResult.Unavailable
        if (manager.canAuthenticate(allowedAuthenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            return BiometricResult.Unavailable
        }

        val prompt = try {
            BiometricPrompt.Builder(appContext)
                .setTitle(reason)
                .setAllowedAuthenticators(allowedAuthenticators)
                .build()
        } catch (failure: Throwable) {
            // A prompt that cannot be built (missing permission, unsupported
            // combination) must not fall through to success.
            return BiometricResult.Failed(failure.message ?: "biometric prompt could not be shown")
        }

        return try {
            suspendCancellableCoroutine { continuation ->
                val cancellationSignal = CancellationSignal()
                val callback = object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: BiometricPrompt.AuthenticationResult,
                    ) {
                        if (continuation.isActive) continuation.resume(BiometricResult.Succeeded)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                        if (continuation.isActive) {
                            continuation.resume(
                                androidBiometricPromptResult(
                                    errorCode = errorCode,
                                    message = errString?.toString().orEmpty(),
                                ),
                            )
                        }
                    }

                    override fun onAuthenticationFailed() {
                        // A single rejected scan. The prompt stays up and lets the
                        // user retry, so this is deliberately not a terminal result.
                    }
                }
                continuation.invokeOnCancellation { cancellationSignal.cancel() }
                prompt.authenticate(cancellationSignal, appContext.mainExecutor, callback)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            BiometricResult.Failed(failure.message ?: "biometric authentication failed")
        }
    }
}

/**
 * Maps a `BiometricPrompt.AuthenticationCallback.onAuthenticationError` code to
 * the domain result. Kept top-level and `internal` so the mapping is unit-tested
 * without an emulator.
 *
 * - user/system cancellation → [BiometricResult.Cancelled];
 * - "nothing enrolled" / "no credential" / no usable hardware → [BiometricResult.Unavailable];
 * - everything else → a [BiometricResult.Failed] carrying the platform message.
 */
internal fun androidBiometricPromptResult(errorCode: Int, message: String): BiometricResult = when (errorCode) {
    BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED,
    BiometricPrompt.BIOMETRIC_ERROR_CANCELED -> BiometricResult.Cancelled

    BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS,
    BiometricPrompt.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL,
    BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT,
    BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE,
    BiometricPrompt.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> BiometricResult.Unavailable

    else -> BiometricResult.Failed(message.ifBlank { "biometric authentication failed ($errorCode)" })
}
