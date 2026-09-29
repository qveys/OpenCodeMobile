package org.opencodemobile.shared.persistence.cache

import app.cash.sqldelight.db.QueryResult
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
 *
 * The driver's connections are created lazily, so the DB file does not exist
 * right after construction. This provider therefore **forces the first open**
 * (which also runs the schema `CREATE`), then applies the file protections —
 * otherwise the first-launch DB (and its `-wal`/`-shm`) would be backed up
 * before the exclusion is set.
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
        // Force a real open (and the schema CREATE) so the DB and its
        // companions exist before we mark them; SQLite creates -wal/-shm
        // lazily. `execute` maps to `executeUpdateDelete`, which cannot run a
        // row-returning statement, so use `executeQuery` and step once.
        driver.executeQuery(
            identifier = null,
            sql = "SELECT 1",
            mapper = { cursor ->
                cursor.next()
                QueryResult.Unit
            },
            parameters = 0,
        )
        IosCacheFileProtection.protectCacheFiles(directory)
        driver
    }

    override suspend fun deleteLocalCache(): Boolean = withContext(ioDispatcher) {
        val directory = IosCacheFileProtection.cacheDirectoryPath()
        val paths = IosCacheFileProtection.cacheFilePaths(directory)
        val fileManager = NSFileManager.defaultManager
        paths.forEach { fileManager.removeItemAtPath(it, null) }
        paths.none { fileManager.fileExistsAtPath(it) }
    }
}
