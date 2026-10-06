package org.opencodemobile.shared.persistence.cache

import app.cash.sqldelight.db.SqlDriver

/**
 * Creates the platform-encrypted cache driver and deletes the local cache
 * files, including the SQLite `-wal`, `-shm`, and `-journal` companions.
 *
 * Android builds the driver over SQLCipher with a passphrase held in the
 * Keystore; iOS relies on OS Data Protection plus backup exclusion
 * (`docs/ARCHITECTURE.md` §"Local cache encryption at rest").
 */
public interface CacheDriverProvider {
    /**
     * Creates (or opens) the encrypted driver.
     *
     * @throws Throwable when the underlying DB cannot be opened, for example
     *   because the encryption key no longer matches the file after key loss.
     *   [CacheDatabase] treats that as a cache miss and rebuilds.
     */
    public suspend fun createDriver(): SqlDriver

    /**
     * Deletes the cache DB and all of its companion files.
     *
     * @return true when none of the cache files remain on disk. A false result
     *   means the wipe failed, so [CacheDatabase] must not blindly recreate over
     *   an undecryptable file.
     */
    public suspend fun deleteLocalCache(): Boolean
}
