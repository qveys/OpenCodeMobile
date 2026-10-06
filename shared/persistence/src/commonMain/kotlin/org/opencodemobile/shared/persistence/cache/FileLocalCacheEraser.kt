package org.opencodemobile.shared.persistence.cache

import kotlin.coroutines.cancellation.CancellationException
import org.opencodemobile.shared.domain.cache.CacheKeyStore
import org.opencodemobile.shared.domain.cache.LocalCacheEraser

/**
 * Encrypted-cache half of "Tout effacer" (ADR 0009 §2.1.3).
 *
 * It composes two primitives that already exist and removes exactly what the
 * ADR requires:
 *
 * 1. [CacheDatabase.eraseLocalFiles] closes the driver and deletes the
 *    SQLDelight database plus its `-wal`/`-shm`/`-journal` companions; and
 * 2. [CacheKeyStore.deletePassphrase] drops the at-rest key material, so a file
 *    that somehow survived could not be decrypted on the next open.
 *
 * The key material is deleted even when the file deletion reports a residue, so
 * the two halves fail independently and the caller learns about either.
 */
public class FileLocalCacheEraser(
    private val database: CacheDatabase,
    private val keyStore: CacheKeyStore,
) : LocalCacheEraser {

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // either half may fail; the boolean reports it
    override suspend fun eraseLocalCache(): Boolean {
        val filesErased = database.eraseLocalFiles()
        val keyErased = try {
            keyStore.deletePassphrase()
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            false
        }
        return filesErased && keyErased
    }
}
