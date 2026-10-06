package org.opencodemobile.shared.security.localaccess

import android.content.Context
import android.content.SharedPreferences
import org.opencodemobile.shared.domain.localaccess.LocalAccessSettings
import org.opencodemobile.shared.domain.localaccess.LocalAccessSettingsStore

/**
 * Android [LocalAccessSettingsStore] backed by [SharedPreferences].
 *
 * These are device preferences, not secrets: no encryption is required and the
 * file must not be mistaken for the Keystore-backed credential store. Keep it
 * out of cloud backup with the rest of the app (`android:allowBackup="false"`
 * on the host manifest).
 */
public class AndroidLocalAccessSettingsStore(
    context: Context,
) : LocalAccessSettingsStore {

    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val delegate = PersistentLocalAccessSettingsStore(
        object : SettingsKeyValue {
            override fun getBoolean(key: String): Boolean? =
                if (preferences.contains(key)) preferences.getBoolean(key, false) else null

            override fun putBoolean(key: String, value: Boolean) {
                preferences.edit().putBoolean(key, value).apply()
            }
        },
    )

    override fun load(): LocalAccessSettings = delegate.load()

    override fun save(settings: LocalAccessSettings): Unit = delegate.save(settings)

    public companion object {
        public const val PREFERENCES_NAME: String = "opencodemobile_local_access"
    }
}
