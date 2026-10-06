package org.opencodemobile.shared.security.biometric

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.opencodemobile.shared.domain.permission.BiometricResult
import platform.LocalAuthentication.LAErrorAppCancel
import platform.LocalAuthentication.LAErrorAuthenticationFailed
import platform.LocalAuthentication.LAErrorBiometryNotAvailable
import platform.LocalAuthentication.LAErrorBiometryNotEnrolled
import platform.LocalAuthentication.LAErrorPasscodeNotSet
import platform.LocalAuthentication.LAErrorSystemCancel
import platform.LocalAuthentication.LAErrorUserCancel
import platform.LocalAuthentication.LAErrorUserFallback

/**
 * iOS platform test for the fail-closed mapping of an `LAContext` failure.
 *
 * Deterministic on the simulator: it asserts the pure mapping, not a real
 * prompt. The "no authenticator at all" path is covered by
 * `canEvaluatePolicy` returning `false` in [IosBiometricAuthenticator]; a mapping
 * that promoted "biometry not enrolled" or "no passcode" to success would
 * reopen the T2 approval gate, so each `LAError` class is asserted explicitly.
 */
class IosBiometricErrorMappingTest {

    @Test
    fun userCancellationIsCancelled() {
        assertEquals(
            BiometricResult.Cancelled,
            iosBiometricPromptResult(LAErrorUserCancel, "cancelled"),
        )
    }

    @Test
    fun systemAndAppCancellationAreCancelled() {
        assertEquals(
            BiometricResult.Cancelled,
            iosBiometricPromptResult(LAErrorSystemCancel, "system cancelled"),
        )
        assertEquals(
            BiometricResult.Cancelled,
            iosBiometricPromptResult(LAErrorAppCancel, "app cancelled"),
        )
    }

    @Test
    fun thePasscodeFallbackTapIsACancellation() {
        assertEquals(
            BiometricResult.Cancelled,
            iosBiometricPromptResult(LAErrorUserFallback, "use passcode"),
        )
    }

    @Test
    fun biometryNotEnrolledIsUnavailableNeverSuccess() {
        assertEquals(
            BiometricResult.Unavailable,
            iosBiometricPromptResult(LAErrorBiometryNotEnrolled, "not enrolled"),
        )
    }

    @Test
    fun noPasscodeSetIsUnavailableNeverSuccess() {
        assertEquals(
            BiometricResult.Unavailable,
            iosBiometricPromptResult(LAErrorPasscodeNotSet, "no passcode"),
        )
    }

    @Test
    fun biometryNotAvailableIsUnavailableNeverSuccess() {
        assertEquals(
            BiometricResult.Unavailable,
            iosBiometricPromptResult(LAErrorBiometryNotAvailable, "unavailable"),
        )
    }

    @Test
    fun anAuthenticationFailureKeepsThePlatformMessage() {
        val result = iosBiometricPromptResult(LAErrorAuthenticationFailed, "authentication failed")

        val failure = assertIs<BiometricResult.Failed>(result)
        assertEquals("authentication failed", failure.reason)
    }

    @Test
    fun aMissingErrorCodeIsAFailureNotSuccess() {
        val result = iosBiometricPromptResult(code = null, message = "")

        assertIs<BiometricResult.Failed>(result)
    }
}
