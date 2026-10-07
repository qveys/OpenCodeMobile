package org.opencodemobile.features.settings

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * F1: the settings screen must not offer an inert capture-blocking switch. On
 * Android the capability is real, so the switch is rendered.
 */
class ScreenCaptureBlockingTest {

    @Test
    fun androidSupportsScreenCaptureBlocking() {
        assertTrue(platformSupportsScreenCaptureBlocking())
    }
}
