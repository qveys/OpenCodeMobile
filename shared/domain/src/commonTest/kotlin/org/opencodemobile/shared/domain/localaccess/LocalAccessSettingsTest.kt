package org.opencodemobile.shared.domain.localaccess

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * §7.3 / Q12 → B: the defaults are the contract, not a convention.
 *
 * - multitask masking is on by default;
 * - screen-capture blocking is off by default (masking must not enable it);
 * - biometrics are optional (off by default) and never a server credential.
 */
class LocalAccessSettingsTest {

    @Test
    fun maskingIsEnabledByDefault() {
        assertTrue(LocalAccessSettings.Default.multitaskMaskingEnabled)
    }

    @Test
    fun captureBlockingIsDisabledByDefault() {
        assertFalse(LocalAccessSettings.Default.screenCaptureBlockingEnabled)
    }

    @Test
    fun biometricsAreOptionalAndOffByDefault() {
        assertFalse(LocalAccessSettings.Default.optionalBiometricsEnabled)
    }

    @Test
    fun anEmptyInstanceCarriesTheSameDefaultsAsTheCompanion() {
        assertEquals(LocalAccessSettings.Default, LocalAccessSettings())
    }
}
