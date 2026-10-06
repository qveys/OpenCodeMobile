package org.opencodemobile.shared.security.biometric

import android.hardware.biometrics.BiometricPrompt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.opencodemobile.shared.domain.permission.BiometricResult

/**
 * Android platform test for the fail-closed mapping of a `BiometricPrompt`
 * terminal error. This is the part of the Android `actual` that can be proven
 * deterministically on the JVM: no emulator, no enrolled biometric.
 *
 * A mapping that turned "no credential enrolled" into `Succeeded`, or that
 * dropped a cancellation, would defeat the T2 approval gate, so each class of
 * `BiometricPrompt` error is asserted explicitly.
 */
class AndroidBiometricErrorMappingTest {

    @Test
    fun userCancellationIsCancelled() {
        assertEquals(
            BiometricResult.Cancelled,
            androidBiometricPromptResult(BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED, "cancelled"),
        )
    }

    @Test
    fun systemCancellationIsCancelled() {
        assertEquals(
            BiometricResult.Cancelled,
            androidBiometricPromptResult(BiometricPrompt.BIOMETRIC_ERROR_CANCELED, "cancelled"),
        )
    }

    @Test
    fun noBiometricsEnrolledIsUnavailableNeverSuccess() {
        assertEquals(
            BiometricResult.Unavailable,
            androidBiometricPromptResult(BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS, "none enrolled"),
        )
    }

    @Test
    fun noDeviceCredentialIsUnavailableNeverSuccess() {
        assertEquals(
            BiometricResult.Unavailable,
            androidBiometricPromptResult(BiometricPrompt.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL, "no credential"),
        )
    }

    @Test
    fun absentHardwareIsUnavailableNeverSuccess() {
        assertEquals(
            BiometricResult.Unavailable,
            androidBiometricPromptResult(BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT, "no hardware"),
        )
    }

    @Test
    fun lockoutIsAFailureAndKeepsThePlatformMessage() {
        val result = androidBiometricPromptResult(BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT, "too many attempts")

        val failure = assertIs<BiometricResult.Failed>(result)
        assertEquals("too many attempts", failure.reason)
    }

    @Test
    fun anUnknownErrorCodeIsAFailureNotSuccess() {
        val result = androidBiometricPromptResult(errorCode = 4242, message = "")

        assertIs<BiometricResult.Failed>(result)
    }
}
