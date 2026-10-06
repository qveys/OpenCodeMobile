package org.opencodemobile.shared.persistence.cache

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.opencodemobile.shared.domain.cache.CachedTranscriptMessage
import org.opencodemobile.shared.domain.cache.ConnectionState
import org.opencodemobile.shared.domain.cache.ConnectivityMutationGate
import org.opencodemobile.shared.security.cache.AndroidKeystoreCacheKeyStore

/**
 * On-device "no residue" proof for the cache half of "Tout effacer" (OPE-275 /
 * ADR 0009 §2.1.3), against the **real** SQLCipher + Keystore composition.
 *
 * The unit test uses a fake provider, so it cannot prove the sidecars are gone.
 * This test opens the real encrypted cache, writes a message so the database and
 * its `-wal`/`-shm` companions exist on disk, runs [FileLocalCacheEraser], and
 * asserts every cache artifact and the key material are gone (review F5).
 */
@Suppress("InjectDispatcher") // Instrumented test: real Dispatchers.IO on purpose; DI is not wired in tests.
class FileLocalCacheEraserInstrumentedTest {

    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun eraseLeavesNoDatabaseFileSidecarOrKeyMaterial() = runBlocking {
        val keyStore = AndroidKeystoreCacheKeyStore(context)
        keyStore.deletePassphrase()
        val provider = SqlCipherCacheDriverProvider(context, keyStore)
        provider.deleteLocalCache()

        val database = CacheDatabase(provider, Dispatchers.IO)
        database.writer(ConnectivityMutationGate(ConnectionState.Online)).putTranscriptMessage(
            CachedTranscriptMessage("s1", "p1", "sess1", 1L, "user", "residue", 1L),
            keepLast = 100,
        )

        val erased = FileLocalCacheEraser(database, keyStore).eraseLocalCache()

        assertTrue(erased, "the erase must report a complete wipe")
        val databaseFile = context.getDatabasePath(CACHE_DATABASE_NAME)
        for (artifact in listOf("", "-wal", "-shm", "-journal")) {
            assertFalse(
                File(databaseFile.path + artifact).exists(),
                "cache artifact '${databaseFile.name}$artifact' must be gone after the erase",
            )
        }
        assertNull(keyStore.existingPassphrase(), "the cache key material must be gone after the erase")
        // F4: the wrapping Keystore key must be removed too, not just the
        // wrapped passphrase entry.
        val androidKeyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertFalse(
            androidKeyStore.containsAlias("opencodemobile.cache.passphrase_key"),
            "the cache wrapping key alias must be gone after the erase",
        )
    }
}
