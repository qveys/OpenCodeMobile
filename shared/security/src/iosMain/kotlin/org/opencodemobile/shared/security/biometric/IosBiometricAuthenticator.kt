@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
@file:Suppress("TooGenericExceptionCaught")

package org.opencodemobile.shared.security.biometric

import kotlin.coroutines.resume
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import org.opencodemobile.shared.domain.permission.BiometricAuthenticator
import org.opencodemobile.shared.domain.permission.BiometricResult
import platform.Foundation.NSError
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAErrorAppCancel
import platform.LocalAuthentication.LAErrorBiometryNotAvailable
import platform.LocalAuthentication.LAErrorBiometryNotEnrolled
import platform.LocalAuthentication.LAErrorPasscodeNotSet
import platform.LocalAuthentication.LAErrorSystemCancel
import platform.LocalAuthentication.LAErrorUserCancel
import platform.LocalAuthentication.LAErrorUserFallback
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthentication

/**
 * iOS `actual` of the [BiometricAuthenticator] port (§4 D4 / §7.3).
 *
 * It evaluates `LAPolicyDeviceOwnerAuthentication` with `LAContext`: Face ID /
 * Touch ID first, with the device passcode as the OS-provided fallback — the
 * biometric-or-device-credential policy the port documents.
 *
 * Fail-closed:
 * - `canEvaluatePolicy` is checked first; when no biometry and no passcode are
 *   set up it returns `false`, so the result is [BiometricResult.Unavailable].
 * - A user/system cancellation → [BiometricResult.Cancelled].
 * - Any other failure, and any thrown framework error, is a non-success result.
 *
 * A fresh [LAContext] is created per call: a context caches its own successful
 * evaluation, and reusing one would let a later call succeed without asking the
 * user anything.
 *
 * The app must declare `NSFaceIDUsageDescription` in its `Info.plist`; without
 * it iOS terminates the process the first time a policy is evaluated.
 */
public class IosBiometricAuthenticator : BiometricAuthenticator {

    override suspend fun authenticate(reason: String): BiometricResult {
        val context = LAContext()
        // The NSError** out-parameter has no binding type; allocate it so the
        // availability query can be read back, exactly as the platform examples do.
        val available = memScoped {
            val errorVar: ObjCObjectVar<NSError?> = alloc()
            context.canEvaluatePolicy(LAPolicyDeviceOwnerAuthentication, errorVar.ptr)
        }
        if (!available) {
            return BiometricResult.Unavailable
        }

        return try {
            suspendCancellableCoroutine { continuation ->
                context.evaluatePolicy(LAPolicyDeviceOwnerAuthentication, reason) { success, error ->
                    if (!continuation.isActive) return@evaluatePolicy
                    continuation.resume(
                        if (success) {
                            BiometricResult.Succeeded
                        } else {
                            iosBiometricPromptResult(
                                code = error?.code,
                                message = error?.localizedDescription.orEmpty(),
                            )
                        },
                    )
                }
                continuation.invokeOnCancellation {
                    // Dismisses the sheet; the later callback carries
                    // LAErrorAppCancel and is dropped by the isActive check.
                    context.invalidate()
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            BiometricResult.Failed(failure.message ?: "biometric authentication failed")
        }
    }
}

/**
 * Maps an `LAError` code from a failed `evaluatePolicy` to the domain result.
 * Kept top-level and `internal` so the mapping is unit-tested without a device.
 *
 * - user/system/app cancellation and the fallback tap → [BiometricResult.Cancelled];
 * - biometry absent, not enrolled, or no passcode set → [BiometricResult.Unavailable];
 * - everything else → a [BiometricResult.Failed] carrying the platform message.
 */
internal fun iosBiometricPromptResult(code: Long?, message: String): BiometricResult = when (code) {
    LAErrorUserCancel,
    LAErrorSystemCancel,
    LAErrorAppCancel,
    LAErrorUserFallback -> BiometricResult.Cancelled

    LAErrorBiometryNotAvailable,
    LAErrorBiometryNotEnrolled,
    LAErrorPasscodeNotSet -> BiometricResult.Unavailable

    else -> BiometricResult.Failed(message.ifBlank { "biometric authentication failed" })
}
