package org.opencodemobile.shared.security.localaccess

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.opencodemobile.shared.domain.localaccess.LocalAccessSettings

/**
 * The defaults and round trip of the persisted local-access settings.
 *
 * A store that silently defaulted masking to `false` (or capture blocking to
 * `true`) would regress §7.3 / Q12 → B, so those two are asserted explicitly on
 * an **empty** backend, i.e. before the user ever opened the settings.
 */
class PersistentLocalAccessSettingsStoreTest {

    private class InMemoryKeyValue : SettingsKeyValue {
        val values = mutableMapOf<String, Boolean>()
        override fun getBoolean(key: String): Boolean? = values[key]
        override fun putBoolean(key: String, value: Boolean) {
            values[key] = value
        }
    }

    @Test
    fun emptyBackendYieldsTheMandatedDefaults() {
        val store = PersistentLocalAccessSettingsStore(InMemoryKeyValue())

        val loaded = store.load()

        assertEquals(LocalAccessSettings.Default, loaded)
        assertTrue(loaded.multitaskMaskingEnabled, "masking must be on by default")
        assertFalse(loaded.screenCaptureBlockingEnabled, "capture blocking must be off by default")
        assertFalse(loaded.optionalBiometricsEnabled, "biometrics must be optional")
    }

    @Test
    fun roundTripsEveryField() {
        val backend = InMemoryKeyValue()
        val store = PersistentLocalAccessSettingsStore(backend)
        val settings = LocalAccessSettings(
            optionalBiometricsEnabled = true,
            multitaskMaskingEnabled = false,
            screenCaptureBlockingEnabled = true,
        )

        store.save(settings)

        assertEquals(settings, store.load())
        assertEquals(true, backend.values[PersistentLocalAccessSettingsStore.KEY_OPTIONAL_BIOMETRICS])
        assertEquals(false, backend.values[PersistentLocalAccessSettingsStore.KEY_MULTITASK_MASKING])
        assertEquals(true, backend.values[PersistentLocalAccessSettingsStore.KEY_SCREEN_CAPTURE_BLOCKING])
    }

    @Test
    fun aBackendWithOnlyOneKeyDefaultsTheOthersIndependently() {
        val backend = InMemoryKeyValue()
        backend.putBoolean(PersistentLocalAccessSettingsStore.KEY_MULTITASK_MASKING, false)
        val store = PersistentLocalAccessSettingsStore(backend)

        val loaded = store.load()

        assertFalse(loaded.multitaskMaskingEnabled)
        assertFalse(loaded.screenCaptureBlockingEnabled)
        assertFalse(loaded.optionalBiometricsEnabled)
    }

    @Test
    fun anAbsentKeyReadsAsNullAtTheBackendBoundary() {
        val backend = InMemoryKeyValue()
        assertNull(backend.getBoolean(PersistentLocalAccessSettingsStore.KEY_MULTITASK_MASKING))
    }
}
