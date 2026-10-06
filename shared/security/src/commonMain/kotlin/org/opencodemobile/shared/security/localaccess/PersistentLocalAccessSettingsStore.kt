package org.opencodemobile.shared.security.localaccess

import org.opencodemobile.shared.domain.localaccess.LocalAccessSettings
import org.opencodemobile.shared.domain.localaccess.LocalAccessSettingsStore

/**
 * Minimal boolean key/value backend the platform stores implement.
 *
 * [getBoolean] returns `null` for a key that was never written, so the store can
 * apply [LocalAccessSettings.Default] per field instead of inventing a value.
 */
public interface SettingsKeyValue {
    public fun getBoolean(key: String): Boolean?
    public fun putBoolean(key: String, value: Boolean)
}

/**
 * [LocalAccessSettingsStore] over a [SettingsKeyValue] backend.
 *
 * Platform stores (`AndroidLocalAccessSettingsStore`, `IosLocalAccessSettingsStore`)
 * only adapt SharedPreferences / `NSUserDefaults` to [SettingsKeyValue]; the
 * defaults and key names live here so both platforms stay byte-compatible and
 * this logic is covered by a common unit test.
 */
public class PersistentLocalAccessSettingsStore(
    private val keyValue: SettingsKeyValue,
) : LocalAccessSettingsStore {

    override fun load(): LocalAccessSettings {
        val defaults = LocalAccessSettings.Default
        return LocalAccessSettings(
            optionalBiometricsEnabled = keyValue.getBoolean(KEY_OPTIONAL_BIOMETRICS)
                ?: defaults.optionalBiometricsEnabled,
            multitaskMaskingEnabled = keyValue.getBoolean(KEY_MULTITASK_MASKING)
                ?: defaults.multitaskMaskingEnabled,
            screenCaptureBlockingEnabled = keyValue.getBoolean(KEY_SCREEN_CAPTURE_BLOCKING)
                ?: defaults.screenCaptureBlockingEnabled,
        )
    }

    override fun save(settings: LocalAccessSettings) {
        keyValue.putBoolean(KEY_OPTIONAL_BIOMETRICS, settings.optionalBiometricsEnabled)
        keyValue.putBoolean(KEY_MULTITASK_MASKING, settings.multitaskMaskingEnabled)
        keyValue.putBoolean(KEY_SCREEN_CAPTURE_BLOCKING, settings.screenCaptureBlockingEnabled)
    }

    public companion object {
        public const val KEY_OPTIONAL_BIOMETRICS: String = "local_access.optional_biometrics"
        public const val KEY_MULTITASK_MASKING: String = "local_access.multitask_masking"
        public const val KEY_SCREEN_CAPTURE_BLOCKING: String = "local_access.screen_capture_blocking"
    }
}
