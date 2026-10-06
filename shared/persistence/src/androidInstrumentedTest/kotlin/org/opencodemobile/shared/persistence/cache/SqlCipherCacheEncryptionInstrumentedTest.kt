package org.opencodemobile.shared.persistence.cache

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.opencodemobile.shared.domain.cache.CachedTranscriptMessage
import org.opencodemobile.shared.domain.cache.ConnectionState
import org.opencodemobile.shared.domain.cache.ConnectivityMutationGate
import org.opencodemobile.shared.security.cache.AndroidKeystoreCacheKeyStore

/**
 * On-device T3 proof (OP2): the SQLCipher cache is not readable in cleartext,
 * with the **real** Keystore-backed passphrase store.
 *
 * Writes a transcript containing a unique marker, closes the DB, then reads the
 * raw DB file (and its `-wal`/`-shm` companions) off disk and asserts the marker
 * is absent and that the file does not start with the plaintext SQLite header.
 *
 * A second test simulates an invalidated Keystore entry (a biometric
 * re-enrollment) by deleting the stored passphrase: reopening must wipe and
 * rebuild an empty cache, without crashing and without asking the user for
 * anything.
 *
 * This is the composition proof the security review asked for (C2): SQLCipher +
 * passphrase read from the Keystore.
 */
@Suppress("InjectDispatcher") // Instrumented test: real Dispatchers.IO on purpose; DI is not wired in tests.
class SqlCipherCacheEncryptionInstrumentedTest {

    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun transcriptIsNotReadableInTheRawDatabaseFile() = runBlocking {
        val keyStore = AndroidKeystoreCacheKeyStore(context)
        keyStore.deletePassphrase()
        val provider = SqlCipherCacheDriverProvider(context, keyStore)
        provider.deleteLocalCache()

        val database = CacheDatabase(provider, Dispatchers.IO)
        val writer = database.writer(ConnectivityMutationGate(ConnectionState.Online))
        val marker = "S3CR3T-TRANSCRIPT-MARKER-${System.nanoTime()}"
        writer.putTranscriptMessage(
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

        database.close()
        provider.deleteLocalCache()
        keyStore.deletePassphrase()
    }

    @Test
    fun keystoreKeyLossWipesAndRebuildsWithoutCrashing() = runBlocking {
        val keyStore = AndroidKeystoreCacheKeyStore(context)
        keyStore.deletePassphrase()
        val firstProvider = SqlCipherCacheDriverProvider(context, keyStore)
        firstProvider.deleteLocalCache()

        val firstDatabase = CacheDatabase(firstProvider, Dispatchers.IO)
        firstDatabase.writer(ConnectivityMutationGate(ConnectionState.Online)).putTranscriptMessage(
            CachedTranscriptMessage("s1", "p1", "sess1", 1L, "user", "before", 1L),
            keepLast = 100,
        )
        firstDatabase.close()

        // An invalidated Keystore entry reads back as "no passphrase": the next
        // open generates a new key, cannot decrypt the old file, and must wipe
        // and rebuild instead of crashing.
        keyStore.deletePassphrase()

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
        keyStore.deletePassphrase()
    }

    private fun rawDatabaseBytes(databaseFile: File): ByteArray {
        val bytes = ArrayList<Byte>()
        for (path in listOf(databaseFile.path, "${databaseFile.path}-wal", "${databaseFile.path}-shm")) {
            val file = File(path)
            if (file.isFile) bytes.addAll(file.readBytes().toList())
        }
        return bytes.toByteArray()
    }
}
