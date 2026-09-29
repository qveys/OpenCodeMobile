package org.opencodemobile.shared.domain.cache

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The cache must never hold a secret, even though the schema has no secret
 * column: the generic preference table needs a value-level guard (F5).
 */
class CachePreferencePolicyTest {

    @Test
    fun rejectsSecretBearingKeys() {
        listOf(
            "auth_token",
            "authToken",
            "refresh-token",
            "api_key",
            "apiKey",
            "password",
            "user_credential",
            "bearer",
            "private_key",
            "device_pin",
        ).forEach { key ->
            assertTrue(CachePreferencePolicy.isSecretBearingKey(key), "expected '$key' to be secret-bearing")
            assertFailsWith<IllegalArgumentException>("expected '$key' to be refused") {
                CachePreferencePolicy.requireNonSecretKey(key)
            }
        }
    }

    @Test
    fun allowsOrdinaryUiPreferences() {
        listOf(
            "theme",
            "last_opened_project",
            "sidebar.width",
            "language",
            "recent.sessions.limit",
        ).forEach { key ->
            assertFalse(CachePreferencePolicy.isSecretBearingKey(key), "did not expect '$key' to be secret-bearing")
            CachePreferencePolicy.requireNonSecretKey(key)
        }
    }
}
