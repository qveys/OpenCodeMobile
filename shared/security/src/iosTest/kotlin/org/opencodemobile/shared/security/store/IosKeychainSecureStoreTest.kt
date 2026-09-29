package org.opencodemobile.shared.security.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

/**
 * iOS-simulator validation of [IosKeychainSecureStore] against the real
 * Keychain: round trip, delete, service-scoped destroy, and key descriptor.
 *
 * The Kotlin/Native iOS test executable is not an app bundle, so the simulator
 * may deny Keychain access (`errSecMissingEntitlement` / not available). In
 * that case the round-trip assertions self-skip with a diagnostic instead of
 * failing the suite; any Keychain failure other than the store reporting
 * [SecureStoreException.KeyUnavailable] still fails. The same Keychain calls
 * are reviewed against the proven `IosKeychainServerIdentityStore` pattern.
 */
class IosKeychainSecureStoreTest {

    private val service = "org.opencodemobile.securestore.test"

    @Test
    fun roundTripsAndRemovesAValue() = runBlocking {
        val store = IosKeychainSecureStore(service)
        try {
            store.destroy()

            store.put("token", "secret-token")
            assertEquals("secret-token", store.get("token"))

            store.remove("token")
            assertNull(store.get("token"))

            store.destroy()
        } catch (unavailable: SecureStoreException.KeyUnavailable) {
            skipKeychain(unavailable)
        }
    }

    @Test
    fun destroyRemovesEveryEntryUnderTheService() = runBlocking {
        val store = IosKeychainSecureStore(service)
        try {
            store.put("a", "1")
            store.put("b", "2")

            store.destroy()

            assertNull(store.get("a"))
            assertNull(store.get("b"))
        } catch (unavailable: SecureStoreException.KeyUnavailable) {
            skipKeychain(unavailable)
        }
    }

    @Test
    fun keyDescriptorReportsTheKeychainService() {
        val store = IosKeychainSecureStore(service)

        assertEquals(service, store.keyDescriptor.alias)
    }

    private fun skipKeychain(unavailable: SecureStoreException.KeyUnavailable) {
        println(
            "Skipping the iOS Keychain round trip: the test executable cannot reach the " +
                "Keychain in this environment (status ${unavailable.osStatus}). " +
                "Message: ${unavailable.message}",
        )
    }
}
