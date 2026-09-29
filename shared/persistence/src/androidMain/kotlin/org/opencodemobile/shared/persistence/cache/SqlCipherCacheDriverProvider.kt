package org.opencodemobile.shared.persistence.cache

import android.content.Context
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.opencodemobile.shared.domain.cache.CacheKeyStore
import org.opencodemobile.shared.persistence.db.Cache

/**
 * Android [CacheDriverProvider] over SQLCipher (T3 / OP2).
 *
 * The cache DB is encrypted with a random passphrase wrapped by a
 * Keystore-backed [CacheKeyStore] — the same secure-store boundary used for
 * server credentials and identity pins (B2). The passphrase is never persisted
 * in cleartext and the Keystore key never leaves the device.
 *
 * The SQLCipher native core ships inside the `sqlcipher-android` AAR but must be
 * loaded explicitly ([System.loadLibrary]).
 */
public class SqlCipherCacheDriverProvider(
    context: Context,
    private val keyStore: CacheKeyStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CacheDriverProvider {

    private val appContext: Context = context.applicationContext

    override suspend fun createDriver(): SqlDriver = withContext(ioDispatcher) {
        loadSqlCipher()
        val passphrase: ByteArray =
            keyStore.existingPassphrase() ?: keyStore.createPassphrase()
        val driver = AndroidSqliteDriver(
            schema = Cache.Schema,
            context = appContext,
            name = CACHE_DATABASE_NAME,
            factory = SupportOpenHelperFactory(passphrase),
        )
        // Force the file open here so a passphrase that no longer matches the
        // file (key loss) fails inside CacheDatabase's recovery path instead of
        // on the first user-visible read. `execute` maps to
        // `executeUpdateDelete`, which cannot run a row-returning statement, so
        // use `executeQuery` and step the cursor once.
        driver.executeQuery(
            identifier = null,
            sql = "SELECT 1",
            mapper = { cursor ->
                cursor.next()
                QueryResult.Unit
            },
            parameters = 0,
        )
        driver
    }

    override suspend fun deleteLocalCache(): Boolean = withContext(ioDispatcher) {
        val databaseFile: File = appContext.getDatabasePath(CACHE_DATABASE_NAME)
        val files = listOf(databaseFile) + companionFiles(databaseFile)
        files.forEach { runCatching { it.delete() } }
        // Report whether the wipe actually removed everything, so the caller
        // does not reopen over a file it could not delete.
        files.none { it.exists() }
    }

    private fun loadSqlCipher() {
        System.loadLibrary(SQLCIPHER_NATIVE_LIBRARY)
    }

    private companion object {
        const val SQLCIPHER_NATIVE_LIBRARY = "sqlcipher"

        fun companionFiles(databaseFile: File): List<File> =
            listOf("-wal", "-shm", "-journal").map { File(databaseFile.path + it) }
    }
}
