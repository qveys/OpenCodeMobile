package org.opencodemobile.shared.persistence.cache

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencodemobile.shared.persistence.db.Cache
import platform.Foundation.NSFileManager

/**
 * iOS [CacheDriverProvider] backed by OS Data Protection (T3 / OP2 iOS bar).
 *
 * The DB is created inside [IosCacheFileProtection]'s protected directory; its
 * files are marked `NSFileProtectionCompleteUnlessOpen` and excluded from
 * backup. No app-managed passphrase is used on iOS — SQLCipher-for-iOS is
 * deferred — so no [org.opencodemobile.shared.domain.cache.CacheKeyStore] is
 * involved.
 */
@OptIn(ExperimentalForeignApi::class)
public class DataProtectionCacheDriverProvider(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : CacheDriverProvider {

    override suspend fun createDriver(): SqlDriver = withContext(ioDispatcher) {
        val directory = IosCacheFileProtection.ensureProtectedCacheDirectory()
        val driver = NativeSqliteDriver(
            schema = Cache.Schema,
            name = CACHE_DATABASE_NAME,
            onConfiguration = { configuration ->
                configuration.copy(
                    extendedConfig = configuration.extendedConfig.copy(basePath = directory),
                )
            },
        )
        // SQLite creates -wal/-shm lazily, so re-apply protection after the open.
        IosCacheFileProtection.protectCacheFiles(directory)
        driver
    }

    override suspend fun deleteLocalCache(): Unit = withContext(ioDispatcher) {
        val directory = IosCacheFileProtection.cacheDirectoryPath()
        IosCacheFileProtection.cacheFilePaths(directory).forEach { path ->
            NSFileManager.defaultManager.removeItemAtPath(path, null)
        }
    }
}
