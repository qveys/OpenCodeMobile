package org.opencodemobile.shared.persistence.cache

import app.cash.sqldelight.db.SqlDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.opencodemobile.shared.domain.cache.CacheKeyStore

/**
 * "No residue" proof for the encrypted-cache half of "Tout effacer"
 * (OPE-275 / ADR 0009 §2.1.3): the database file, its `-wal`/`-shm` companions
 * and the key material are all removed.
 *
 * The driver is never created on this path (the erase only closes and deletes),
 * so a provider whose `createDriver` throws is a faithful fake.
 */
class FileLocalCacheEraserTest {

    @Test
    fun `erase deletes the cache files and the key material`() = runTest {
        val provider = FakeDriverProvider()
        val keyStore = FakeKeyStore()
        val eraser = FileLocalCacheEraser(CacheDatabase(provider), keyStore)

        assertTrue(eraser.eraseLocalCache())

        assertEquals(1, provider.deleteCalls)
        assertEquals(1, keyStore.deleteCalls)
    }

    @Test
    fun `a cache file residue is reported`() = runTest {
        val provider = FakeDriverProvider(deleted = false)
        val keyStore = FakeKeyStore()
        val eraser = FileLocalCacheEraser(CacheDatabase(provider), keyStore)

        assertFalse(eraser.eraseLocalCache())

        // The key is still dropped even when a file survived, so the residue is
        // not decryptable; the false result makes the caller surface it.
        assertEquals(1, keyStore.deleteCalls)
    }

    @Test
    fun `a key-deletion failure is reported`() = runTest {
        val provider = FakeDriverProvider()
        val keyStore = FakeKeyStore(failOnDelete = true)
        val eraser = FileLocalCacheEraser(CacheDatabase(provider), keyStore)

        assertFalse(eraser.eraseLocalCache())

        assertEquals(1, provider.deleteCalls)
        assertEquals(1, keyStore.deleteCalls)
    }

    private class FakeDriverProvider(
        private val deleted: Boolean = true,
    ) : CacheDriverProvider {
        var deleteCalls: Int = 0
            private set

        override suspend fun createDriver(): SqlDriver =
            throw UnsupportedOperationException("the erase path must not create a driver")

        override suspend fun deleteLocalCache(): Boolean {
            deleteCalls += 1
            return deleted
        }
    }

    private class FakeKeyStore(
        private val failOnDelete: Boolean = false,
    ) : CacheKeyStore {
        var deleteCalls: Int = 0
            private set

        override suspend fun existingPassphrase(): ByteArray? = null

        override suspend fun createPassphrase(): ByteArray = ByteArray(32)

        override suspend fun deletePassphrase() {
            deleteCalls += 1
            if (failOnDelete) throw IllegalStateException("keystore unavailable")
        }
    }
}
