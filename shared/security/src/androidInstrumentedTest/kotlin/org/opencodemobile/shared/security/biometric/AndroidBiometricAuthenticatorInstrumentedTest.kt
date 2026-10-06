package org.opencodemobile.shared.security.biometric

import android.content.Context
import android.hardware.biometrics.BiometricManager
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.opencodemobile.shared.domain.permission.BiometricResult

/**
 * On-device validation of the Android fail-closed contract.
 *
 * On the CI emulator (and any device without an enrolled biometric or device
 * credential) `canAuthenticate` is not `BIOMETRIC_SUCCESS`, so the authenticator
 * must answer [BiometricResult.Unavailable] without ever showing a prompt and
 * without ever reporting success. The test self-skips on a device that *does*
 * have an authenticator enrolled, because driving the real system dialog is not
 * deterministic in CI (that path is exercised by the L4 real-device recipe).
 */
class AndroidBiometricAuthenticatorInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun noEnrolledAuthenticatorIsUnavailableNeverSuccess() = runBlocking {
        val allowed = BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val available = BiometricManager.from(context).canAuthenticate(allowed) ==
            BiometricManager.BIOMETRIC_SUCCESS
        assumeTrue("device has an enrolled authenticator; skip the no-authenticator path", !available)

        val result = AndroidBiometricAuthenticator(context).authenticate("Confirm the local-access test")

        assertEquals(BiometricResult.Unavailable, result)
    }
}
