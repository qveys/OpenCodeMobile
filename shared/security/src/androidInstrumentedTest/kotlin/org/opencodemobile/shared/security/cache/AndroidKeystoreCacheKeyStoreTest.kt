package org.opencodemobile.shared.security.cache

import android.content.Context
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * On-device test for the Keystore-backed cache passphrase store (B2 / T3).
 *
 * Proves the passphrase is generated once, read back through the Keystore (a
 * second store instance reads the same value), deleted on demand, and never
 * written in cleartext to the preferences file.
 */
class AndroidKeystoreCacheKeyStoreTest {

    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun generatesReadsAndDeletesThePassphrase() = runBlocking {
        val store = AndroidKeystoreCacheKeyStore(context)
        store.deletePassphrase()

        val created = store.createPassphrase()
        assertEquals(PASSPHRASE_BYTES, created.size)
        assertContentEquals(created, store.existingPassphrase())
        // A fresh instance must read the same passphrase back from the Keystore.
        assertContentEquals(created, AndroidKeystoreCacheKeyStore(context).existingPassphrase())

        store.deletePassphrase()
        assertNull(store.existingPassphrase())
    }

    @Test
    fun passphraseIsNotStoredInPlaintextInPreferences() = runBlocking {
        val store = AndroidKeystoreCacheKeyStore(context)
        store.deletePassphrase()
        val created = store.createPassphrase()

        val preferencesFile = File(
            context.applicationInfo.dataDir,
            "shared_prefs/opencodemobile_cache_key.xml",
        )
        assertTrue(preferencesFile.isFile, "the cache key preferences file should exist")

        val rawHex = created.joinToString(separator = "") { "%02x".format(it) }
        val base64 = Base64.encodeToString(created, Base64.NO_WRAP)
        val xml = preferencesFile.readText()
        assertFalse(xml.contains(rawHex), "the raw passphrase bytes must not be in preferences")
        assertFalse(xml.contains(base64), "the base64 passphrase must not be in preferences")

        store.deletePassphrase()
    }

    private companion object {
        const val PASSPHRASE_BYTES = 32
    }
}
