package org.opencodemobile.shared.security.store

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.security.KeyStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

/**
 * On-device validation of [AndroidKeystoreSecureStore] against the real
 * Android Keystore: round trip, ciphertext-at-rest, delete, destroy, and
 * namespace isolation. Runs on the emulator like the T1 tests (OPE-94).
 */
class AndroidKeystoreSecureStoreInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val namespace = "instrumented-test"
    private val preferencesName = "opencodemobile_secure_store.$namespace"
    private val keyAlias = "opencodemobile.securestore.$namespace"

    @AfterTest
    fun tearDown() {
        runBlocking {
            AndroidKeystoreSecureStore(context, namespace).destroy()
            context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test
    fun roundTripsAValueThroughTheKeystore() = runBlocking {
        val store = AndroidKeystoreSecureStore(context, namespace)

        store.put("token", "super-secret-value")

        assertEquals("super-secret-value", store.get("token"))
    }

    @Test
    fun ciphertextAtRestDoesNotContainThePlaintext() = runBlocking {
        val store = AndroidKeystoreSecureStore(context, namespace)
        val secret = "plaintext-must-not-appear"

        store.put("token", secret)

        val raw = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .all
            .values
            .joinToString(separator = "\n") { it.toString() }
        assertFalse(raw.contains(secret), "the keystore-backed value must never be stored in the clear")
    }

    @Test
    fun missingEntryReadsAsNull() = runBlocking {
        assertNull(AndroidKeystoreSecureStore(context, namespace).get("absent"))
    }

    @Test
    fun removeDeletesOnlyTheTargetEntry() = runBlocking {
        val store = AndroidKeystoreSecureStore(context, namespace)
        store.put("a", "1")
        store.put("b", "2")

        store.remove("a")

        assertNull(store.get("a"))
        assertEquals("2", store.get("b"))
    }

    @Test
    fun destroyRemovesTheEntriesAndTheDedicatedKey() = runBlocking {
        val store = AndroidKeystoreSecureStore(context, namespace)
        store.put("a", "1")

        store.destroy()

        assertNull(store.get("a"))
        assertFalse(androidKeyStore().containsAlias(keyAlias))
    }

    @Test
    fun namespacesAreIsolated() = runBlocking {
        val first = AndroidKeystoreSecureStore(context, namespace)
        val second = AndroidKeystoreSecureStore(context, "$namespace-second")

        first.put("token", "one")
        second.put("token", "two")

        assertEquals("one", first.get("token"))
        assertEquals("two", second.get("token"))
        second.destroy()
    }

    private fun androidKeyStore(): KeyStore =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
}
