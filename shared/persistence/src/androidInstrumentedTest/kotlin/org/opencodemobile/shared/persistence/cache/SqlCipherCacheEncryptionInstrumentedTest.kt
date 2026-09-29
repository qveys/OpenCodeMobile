package org.opencodemobile.shared.persistence.cache

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.db.SqlDriver
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.opencodemobile.shared.domain.cache.CacheKeyStore
import org.opencodemobile.shared.domain.cache.CachedTranscriptMessage

/**
 * On-device T3 proof (OP2): the SQLCipher cache is not readable in cleartext.
 *
 * Writes a transcript containing a unique marker, closes the DB, then reads the
 * raw DB file (and its `-wal`/`-shm` companions) off disk and asserts the marker
 * is absent and that the file does not start with the plaintext SQLite header.
 * Also exercises key loss: after the key changes, reopening wipes and rebuilds
 * the disposable cache without crashing.
 *
 * The passphrase here comes from a test [CacheKeyStore]; the Keystore-backed
 * implementation is covered by `AndroidKeystoreCacheKeyStoreTest` in
 * `shared/security`.
 */
class SqlCipherCacheEncryptionInstrumentedTest {

    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun transcriptIsNotReadableInTheRawDatabaseFile() = runBlocking {
        val keyStore = MutablePassphraseKeyStore()
        val provider = SqlCipherCacheDriverProvider(context, keyStore)
        provider.deleteLocalCache()

        val database = CacheDatabase(provider, Dispatchers.IO)
        val cache = database.open()
        val marker = "S3CR3T-TRANSCRIPT-MARKER-${System.nanoTime()}"
        cache.putTranscriptMessage(
            CachedTranscriptMessage("s1", "p1", "sess1", 1L, "user", marker, 1L),
            keepLast = 100,
        )
        database.close()

        val databaseFile = context.getDatabasePath(CACHE_DATABASE_NAME)
        val rawBytes = rawDatabaseBytes(databaseFile)
        val plaintextHeader = "SQLite format 3\u0000"
        assertTrue(rawBytes.isNotEmpty(), "the cache DB file should exist")
        assertFalse(
            String(rawBytes, Charsets.ISO_8859_1).contains(marker),
            "the transcript marker must not appear in the raw cache file (T3)",
        )
        assertFalse(
            rawBytes.size >= plaintextHeader.length &&
                String(
                    rawBytes.copyOfRange(0, plaintextHeader.length),
                    Charsets.ISO_8859_1,
                ) == plaintextHeader,
            "an encrypted cache DB must not start with the plaintext SQLite header",
        )

        provider.deleteLocalCache()
    }

    @Test
    fun keyLossWipesAndRebuildsWithoutCrashing() = runBlocking {
        val keyStore = MutablePassphraseKeyStore()
        val firstProvider = SqlCipherCacheDriverProvider(context, keyStore)
        firstProvider.deleteLocalCache()

        val firstDatabase = CacheDatabase(firstProvider, Dispatchers.IO)
        firstDatabase.open().putTranscriptMessage(
            CachedTranscriptMessage("s1", "p1", "sess1", 1L, "user", "before", 1L),
            keepLast = 100,
        )
        firstDatabase.close()

        // Simulate an invalidated Keystore entry: the next passphrase differs, so
        // the on-disk file can no longer be decrypted.
        keyStore.rotatePassphrase()

        val secondDatabase = CacheDatabase(
            SqlCipherCacheDriverProvider(context, keyStore),
            Dispatchers.IO,
        )
        val rebuilt = secondDatabase.open()
        assertTrue(
            rebuilt.recentTranscript("s1", "p1", "sess1", limit = 10).isEmpty(),
            "a key loss must rebuild an empty cache from the next snapshot",
        )
        secondDatabase.close()

        firstProvider.deleteLocalCache()
    }

    private fun rawDatabaseBytes(databaseFile: File): ByteArray {
        val bytes = ArrayList<Byte>()
        for (path in listOf(databaseFile.path, "${databaseFile.path}-wal", "${databaseFile.path}-shm")) {
            val file = File(path)
            if (file.isFile) bytes.addAll(file.readBytes().toList())
        }
        return bytes.toByteArray()
    }

    private class MutablePassphraseKeyStore : CacheKeyStore {
        private var passphrase: ByteArray? = null

        override suspend fun existingPassphrase(): ByteArray? = passphrase

        override suspend fun createPassphrase(): ByteArray {
            val created = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            passphrase = created
            return created
        }

        override suspend fun deletePassphrase() {
            passphrase = null
        }

        fun rotatePassphrase() {
            val rotated = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            passphrase = rotated
        }
    }
}
