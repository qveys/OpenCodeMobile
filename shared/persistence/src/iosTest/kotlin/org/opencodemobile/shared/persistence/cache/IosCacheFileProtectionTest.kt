package org.opencodemobile.shared.persistence.cache

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.opencodemobile.shared.domain.cache.CachedTranscriptMessage
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionCompleteUnlessOpen

/**
 * iOS at-rest protection tests (T3 / OP2), run on the simulator.
 *
 * Beyond the deterministic file-set checks, this opens the real
 * [DataProtectionCacheDriverProvider], writes a transcript, and reads the
 * `NSFileProtectionKey` and `NSURLIsExcludedFromBackupKey` resource values back
 * off the created DB and its companions. That is exactly the coverage that
 * catches the first-launch backup-exclusion gap (F1): the driver creates its
 * connections lazily, so only an actual open + write proves the files exist and
 * are protected.
 */
class IosCacheFileProtectionTest {

    @Test
    fun cacheFilePathsCoverTheDatabaseAndItsCompanions() {
        val directory = "/tmp/opencodemobile-cache"
        val database = "$directory/$CACHE_DATABASE_NAME"

        assertEquals(
            listOf(
                database,
                "$database-wal",
                "$database-shm",
                "$database-journal",
            ),
            IosCacheFileProtection.cacheFilePaths(directory),
        )
    }

    @Test
    fun theCacheDirectoryIsDedicatedAndNamed() {
        assertTrue(
            IosCacheFileProtection.cacheDirectoryPath().endsWith("OpenCodeMobileCache"),
            "the cache must live in its own Application Support directory",
        )
    }

    @Test
    fun theCacheDirectoryIsExcludedFromBackupOnFirstLaunch() {
        val directory = IosCacheFileProtection.ensureProtectedCacheDirectory()
        assertTrue(
            IosCacheFileProtection.isExcludedFromBackup(directory),
            "the cache directory must be excluded from backup even before any file exists",
        )
    }

    @Test
    fun openedDatabaseAndCompanionsAreProtectedAndExcluded() = runBlocking<Unit> {
        val provider = DataProtectionCacheDriverProvider(Dispatchers.Default)
        provider.deleteLocalCache()
        val database = CacheDatabase(provider, Dispatchers.Default)
        // Keep the connection open while inspecting the files: a clean close
        // checkpoints and removes `-wal`/`-shm`, which made the previous
        // companion checks vacuous (F1).
        database.open().putTranscriptMessage(
            CachedTranscriptMessage("s1", "p1", "sess1", 1L, "user", "hello", 1L),
            keepLast = 10,
        )

        val directory = IosCacheFileProtection.cacheDirectoryPath()
        val databasePath = IosCacheFileProtection.cacheFilePaths(directory).first()
        val fileManager = NSFileManager.defaultManager

        assertTrue(fileManager.fileExistsAtPath(databasePath), "the cache DB must exist after an open")

        // The DB and the WAL companions created by the open + write must each
        // carry the protection class and the backup exclusion. These checks are
        // unconditional: a companion that is missing or unprotected is a
        // failure, never a silent skip.
        listOf(databasePath, "$databasePath-wal", "$databasePath-shm").forEach { path ->
            assertTrue(
                fileManager.fileExistsAtPath(path),
                "$path must exist while the cache connection is open",
            )
            assertTrue(
                IosCacheFileProtection.isExcludedFromBackup(path),
                "$path must be excluded from iCloud/iTunes backup",
            )
            assertEquals(
                NSFileProtectionCompleteUnlessOpen,
                IosCacheFileProtection.protectionClass(path),
                "$path must carry NSFileProtectionCompleteUnlessOpen",
            )
        }

        database.close()
        provider.deleteLocalCache()
    }

    @Test
    fun protectingAbsentFilesIsSafe() {
        // Must not throw when the DB has not been created yet (first launch).
        IosCacheFileProtection.protectCacheFiles(IosCacheFileProtection.cacheDirectoryPath())
    }
}
