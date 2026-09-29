package org.opencodemobile.shared.persistence.cache

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.opencodemobile.shared.domain.cache.CachedTranscriptMessage
import org.opencodemobile.shared.domain.cache.ConnectionState
import org.opencodemobile.shared.domain.cache.ConnectivityMutationGate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionCompleteUnlessOpen

/**
 * iOS at-rest protection tests (T3 / OP2), run on the simulator.
 *
 * Beyond the deterministic file-set checks, this opens the real
 * [DataProtectionCacheDriverProvider], writes a transcript, and inspects the
 * DB, `-wal`, and `-shm` **while the connection is still open** — a clean close
 * checkpoints and removes the WAL companions, which is what made the earlier
 * companion checks vacuous (F1).
 *
 * Two properties are checked per file:
 *  - `NSURLIsExcludedFromBackupKey` — the actual first-launch gap C1/F1. It is
 *    enforced and observable on the simulator, so it is asserted unconditionally.
 *  - `NSFileProtectionKey` — the Data Protection class. The iOS Simulator does
 *    not implement Data Protection: `setAttributes` never surfaces a class, so
 *    [IosCacheFileProtection.protectionClass] returns null there. The assertion
 *    is therefore made on hosts that report the class (a real device) and
 *    explicitly reported — never silently skipped — where the platform cannot.
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
        database.writer(ConnectivityMutationGate(ConnectionState.Online)).putTranscriptMessage(
            CachedTranscriptMessage("s1", "p1", "sess1", 1L, "user", "hello", 1L),
            keepLast = 10,
        )

        val directory = IosCacheFileProtection.cacheDirectoryPath()
        val databasePath = IosCacheFileProtection.cacheFilePaths(directory).first()
        val fileManager = NSFileManager.defaultManager

        // The directory is protected by the same code path that marks the files.
        // If the host reports a protection class for it, then the class is
        // observable here and the per-file class assertion below is meaningful;
        // on the iOS Simulator Data Protection is a no-op and it is not.
        val protectionClassIsObservable =
            IosCacheFileProtection.protectionClass(directory) != null

        assertTrue(fileManager.fileExistsAtPath(databasePath), "the cache DB must exist after an open")

        // The DB and the WAL companions created by the open + write must exist
        // and be excluded from backup. Those checks are unconditional: a missing
        // or unprotected companion is a failure, never a silent skip.
        listOf(databasePath, "$databasePath-wal", "$databasePath-shm").forEach { path ->
            assertTrue(
                fileManager.fileExistsAtPath(path),
                "$path must exist while the cache connection is open",
            )
            assertTrue(
                IosCacheFileProtection.isExcludedFromBackup(path),
                "$path must be excluded from iCloud/iTunes backup",
            )
            if (protectionClassIsObservable) {
                assertEquals(
                    NSFileProtectionCompleteUnlessOpen,
                    IosCacheFileProtection.protectionClass(path),
                    "$path must carry NSFileProtectionCompleteUnlessOpen",
                )
            }
        }
        if (!protectionClassIsObservable) {
            println(
                "IosCacheFileProtectionTest: the host does not report " +
                    "NSFileProtectionKey (iOS Simulator Data Protection is a no-op); " +
                    "the class assertion runs on a real device.",
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
