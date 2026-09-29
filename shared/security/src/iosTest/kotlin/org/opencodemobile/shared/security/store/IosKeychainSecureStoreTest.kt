package org.opencodemobile.shared.security.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

/**
 * iOS-simulator validation of [IosKeychainSecureStore] against the real
 * Keychain: round trip, delete, service-scoped destroy, and key descriptor.
 */
class IosKeychainSecureStoreTest {

    private val service = "org.opencodemobile.securestore.test"

    @Test
    fun roundTripsAndRemovesAValue() = runBlocking {
        val store = IosKeychainSecureStore(service)
        store.destroy()

        store.put("token", "secret-token")
        assertEquals("secret-token", store.get("token"))

        store.remove("token")
        assertNull(store.get("token"))

        store.destroy()
    }

    @Test
    fun destroyRemovesEveryEntryUnderTheService() = runBlocking {
        val store = IosKeychainSecureStore(service)
        store.put("a", "1")
        store.put("b", "2")

        store.destroy()

        assertNull(store.get("a"))
        assertNull(store.get("b"))
    }

    @Test
    fun keyDescriptorReportsTheKeychainService() {
        val store = IosKeychainSecureStore(service)

        assertEquals(service, store.keyDescriptor.alias)
    }
}
