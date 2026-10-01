package org.opencodemobile.shared.security.biometric

import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import org.opencodemobile.shared.domain.permission.BiometricAuthenticator
import org.opencodemobile.shared.domain.permission.BiometricResult
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthentication

/**
 * iOS [BiometricAuthenticator] (T2): the real `LAContext` gate.
 *
 * Uses `.deviceOwnerAuthentication` so Face ID / Touch ID is preferred with the
 * device passcode as fallback, matching `docs/ARCHITECTURE.md` §"Permission
 * approval confirmation". It fails closed: any error (including "no passcode and
 * no enrolled biometric") is reported as a failure, never as success.
 */
public class IosBiometricAuthenticator : BiometricAuthenticator {

    override suspend fun authenticate(reason: String): BiometricResult =
        suspendCancellableCoroutine { continuation ->
            val context = LAContext()
            continuation.invokeOnCancellation { context.invalidate() }
            context.evaluatePolicy(LAPolicyDeviceOwnerAuthentication, reason) { success, error ->
                if (!continuation.isActive) return@evaluatePolicy
                continuation.resume(
                    if (success) {
                        BiometricResult.Succeeded
                    } else {
                        BiometricResult.Failed(
                            error?.localizedDescription ?: "no biometric or device credential available",
                        )
                    },
                )
            }
        }
}
