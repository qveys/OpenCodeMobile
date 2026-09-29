package org.opencodemobile.shared.security.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import platform.Security.errSecInteractionNotAllowed
import platform.Security.errSecMissingEntitlement
import platform.Security.errSecNotAvailable

/**
 * iOS-simulator validation of [IosKeychainSecureStore] against the real
 * Keychain: round trip, delete, service-scoped destroy, and key descriptor.
 *
 * The Kotlin/Native iOS test executable is not an app bundle, so the simulator
 * may deny Keychain access ([errSecMissingEntitlement] / [errSecNotAvailable] /
 * [errSecInteractionNotAllowed]). Only those environment causes self-skip with
 * a diagnostic; every other store failure is a real regression and fails the
 * suite, so a store that fails every write on a real device is still caught.
 * The same Keychain calls are reviewed against the proven
 * `IosKeychainServerIdentityStore` pattern.
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
            skipOrRethrow(unavailable)
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
            skipOrRethrow(unavailable)
        }
    }

    @Test
    fun keyDescriptorReportsTheKeychainService() {
        val store = IosKeychainSecureStore(service)

        assertEquals(service, store.keyDescriptor.alias)
    }

    private fun skipOrRethrow(unavailable: SecureStoreException.KeyUnavailable) {
        val status = unavailable.osStatus
        if (status != null && status in SKIPPABLE_KEYCHAIN_STATUSES) {
            println(
                "Skipping the iOS Keychain round trip: the test executable cannot reach the " +
                    "Keychain in this environment (status $status). " +
                    "Message: ${unavailable.message}",
            )
            return
        }

        // Any other status is a real store failure, not an environment
        // limitation: rethrow so the round trip fails the suite.
        throw unavailable
    }

    private companion object {
        /**
         * The only `OSStatus` values that mean "this test executable cannot use
         * the Keychain in this environment": the simulator denies the
         * entitlement, the Keychain is unavailable, or the device is locked.
         *
         * Everything else (I/O, decode, duplicate item, auth failure, ...) is a
         * genuine store regression and must fail the test.
         */
        private val SKIPPABLE_KEYCHAIN_STATUSES =
            setOf(errSecMissingEntitlement, errSecNotAvailable, errSecInteractionNotAllowed)
    }
}
