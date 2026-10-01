package org.opencodemobile.shared.security.biometric

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import org.opencodemobile.shared.domain.permission.BiometricAuthenticator
import org.opencodemobile.shared.domain.permission.BiometricResult

/**
 * Android [BiometricAuthenticator] (T2): the real `BiometricPrompt` gate.
 *
 * It accepts either a strong biometric or the device credential (PIN/pattern/
 * password), matching `docs/ARCHITECTURE.md` §"Permission approval confirmation",
 * and it fails closed: no enrolled authenticator, a missing activity, or a prompt
 * error never reports success.
 *
 * The activity is supplied lazily because the prompt must be bound to the current
 * foreground `FragmentActivity`; the composition root wires
 * `activityProvider = { currentActivity }`.
 */
public class AndroidBiometricAuthenticator(
    private val activityProvider: () -> FragmentActivity?,
) : BiometricAuthenticator {

    override suspend fun authenticate(reason: String): BiometricResult {
        val activity = activityProvider() ?: return BiometricResult.Unavailable
        val allowedAuthenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(activity).canAuthenticate(allowedAuthenticators) !=
            BiometricManager.BIOMETRIC_SUCCESS
        ) {
            return BiometricResult.Unavailable
        }

        return suspendCancellableCoroutine { continuation ->
            val executor = ContextCompat.getMainExecutor(activity)
            val prompt = BiometricPrompt(
                activity,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: BiometricPrompt.AuthenticationResult,
                    ) {
                        if (continuation.isActive) continuation.resume(BiometricResult.Succeeded)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (!continuation.isActive) return
                        val outcome = when (errorCode) {
                            BiometricPrompt.ERROR_USER_CANCELED,
                            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                            BiometricPrompt.ERROR_CANCELED,
                            -> BiometricResult.Cancelled

                            else -> BiometricResult.Failed(errString.toString())
                        }
                        continuation.resume(outcome)
                    }

                    override fun onAuthenticationFailed() {
                        // A wrong biometric keeps the system prompt open: do not resume.
                    }
                },
            )
            continuation.invokeOnCancellation { prompt.cancelAuthentication() }
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Confirm permission")
                .setSubtitle(reason)
                .setAllowedAuthenticators(allowedAuthenticators)
                .build()
            prompt.authenticate(promptInfo)
        }
    }
}
