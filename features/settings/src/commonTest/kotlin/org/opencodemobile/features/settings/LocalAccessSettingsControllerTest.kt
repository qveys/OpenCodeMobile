package org.opencodemobile.features.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.opencodemobile.shared.domain.localaccess.LocalAccessSettings
import org.opencodemobile.shared.domain.localaccess.LocalAccessSettingsStore

/**
 * The settings presenter: defaults come from the domain, every toggle is
 * persisted, and the two §7.3 invariants (masking on, capture blocking off)
 * survive a fresh install.
 */
class LocalAccessSettingsControllerTest {

    private class FakeStore(
        var value: LocalAccessSettings = LocalAccessSettings.Default,
    ) : LocalAccessSettingsStore {
        var saves: Int = 0
        override fun load(): LocalAccessSettings = value
        override fun save(settings: LocalAccessSettings) {
            value = settings
            saves++
        }
    }

    @Test
    fun startsFromThePersistedValueNotFromAHardCodedDefault() {
        val persisted = LocalAccessSettings(
            optionalBiometricsEnabled = true,
            multitaskMaskingEnabled = false,
            screenCaptureBlockingEnabled = true,
        )
        val controller = LocalAccessSettingsController(FakeStore(persisted))

        assertEquals(persisted, controller.state.value)
    }

    @Test
    fun freshInstallKeepsMaskingOnAndCaptureBlockingOff() {
        val controller = LocalAccessSettingsController(FakeStore())

        val state = controller.state.value
        assertTrue(state.multitaskMaskingEnabled, "masking must be on by default")
        assertFalse(state.screenCaptureBlockingEnabled, "capture blocking must be off by default")
    }

    @Test
    fun enablingMaskingNeverTurnsOnCaptureBlocking() {
        val store = FakeStore()
        val controller = LocalAccessSettingsController(store)

        controller.setMultitaskMaskingEnabled(true)

        assertFalse(controller.state.value.screenCaptureBlockingEnabled)
        assertFalse(store.value.screenCaptureBlockingEnabled)
    }

    @Test
    fun everyToggleIsPersisted() {
        val store = FakeStore()
        val controller = LocalAccessSettingsController(store)

        controller.setOptionalBiometricsEnabled(true)
        controller.setMultitaskMaskingEnabled(false)
        controller.setScreenCaptureBlockingEnabled(true)

        assertEquals(3, store.saves)
        assertEquals(
            LocalAccessSettings(
                optionalBiometricsEnabled = true,
                multitaskMaskingEnabled = false,
                screenCaptureBlockingEnabled = true,
            ),
            store.value,
        )
        assertEquals(store.value, controller.state.value)
    }
}
