package org.opencodemobile.features.settings

import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * F1: iOS has no public capture-blocking API, so the settings screen hides the
 * capture-blocking control rather than showing a switch that does nothing.
 */
class ScreenCaptureBlockingTest {

    @Test
    fun iosDoesNotSupportScreenCaptureBlocking() {
        assertFalse(platformSupportsScreenCaptureBlocking())
    }
}
