package org.opencodemobile.shared.persistence.cache

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * iOS at-rest protection helper tests (T3 / OP2).
 *
 * These cover the deterministic part — the file set that must be protected and
 * excluded from backup, and the dedicated directory name. Enforcement of
 * `NSFileProtectionCompleteUnlessOpen` and `NSURLIsExcludedFromBackupKey` is a
 * device/simulator property and is validated by the on-device run.
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
    fun protectingAbsentFilesIsSafe() {
        // Must not throw when the DB has not been created yet (first launch).
        IosCacheFileProtection.protectCacheFiles(IosCacheFileProtection.cacheDirectoryPath())
    }
}
