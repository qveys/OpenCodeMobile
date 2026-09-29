package org.opencodemobile.shared.persistence.cache

import android.content.Context
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
 * The cache DB is encrypted with a random passphrase stored through the
 * Keystore-backed [CacheKeyStore] — the same `expect`/`actual` boundary used for
 * server credentials and identity pins (B2). The passphrase is never read from a
 * plain preferences file and never leaves the Keystore in cleartext.
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
        // on the first user-visible read.
        driver.execute(null, "SELECT 1", 0) {}
        driver
    }

    override suspend fun deleteLocalCache(): Unit = withContext(ioDispatcher) {
        val databaseFile: File = appContext.getDatabasePath(CACHE_DATABASE_NAME)
        companionFiles(databaseFile).forEach { runCatching { it.delete() } }
        runCatching { databaseFile.delete() }
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
