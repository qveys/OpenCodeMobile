@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.opencodemobile.shared.security.localaccess

import org.opencodemobile.shared.domain.localaccess.LocalAccessSettings
import org.opencodemobile.shared.domain.localaccess.LocalAccessSettingsStore
import platform.Foundation.NSUserDefaults

/**
 * iOS [LocalAccessSettingsStore] backed by [NSUserDefaults].
 *
 * These are device preferences, not secrets: they deliberately do **not** go
 * through the Keychain. An absent key reads as `null` so the store applies
 * [LocalAccessSettings.Default] per field.
 */
public class IosLocalAccessSettingsStore(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : LocalAccessSettingsStore {

    private val delegate = PersistentLocalAccessSettingsStore(
        object : SettingsKeyValue {
            override fun getBoolean(key: String): Boolean? =
                if (defaults.objectForKey(key) == null) null else defaults.boolForKey(key)

            override fun putBoolean(key: String, value: Boolean) {
                defaults.setBool(value, key)
            }
        },
    )

    override fun load(): LocalAccessSettings = delegate.load()

    override fun save(settings: LocalAccessSettings): Unit = delegate.save(settings)
}
