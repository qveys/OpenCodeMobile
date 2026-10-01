package org.opencodemobile.shared.persistence.cache

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionCompleteUnlessOpen
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask

/**
 * iOS at-rest protection for the local cache (T3 / OP2).
 *
 * V1 uses the platform's Data Protection rather than SQLCipher-for-iOS: the
 * cache DB lives in a dedicated directory whose files are marked
 * [NSFileProtectionCompleteUnlessOpen], which ties decryption to the device
 * passcode. `.complete` remains "to be confirmed" and SQLCipher-for-iOS is
 * deferred (`docs/ARCHITECTURE.md` §"Local cache encryption at rest",
 * ADR 0005 OP2).
 *
 * The DB and its `-wal`/`-shm` companions are also flagged
 * `NSURLIsExcludedFromBackupKey`. The **directory itself** is excluded too, so
 * the files created lazily on first launch (the DB, then `-wal`/`-shm` on the
 * first write) are covered even before they exist.
 *
 * **Residual risk (accepted for V1):** file-class protection does not cover
 * malware with app-sandbox access (the class key lives in the OS) nor an
 * unlocked, stolen device. Those two T3 scenarios stay open on iOS and are
 * re-evaluated when `.complete` is confirmed or SQLCipher-for-iOS lands.
 *
 * This is a floor, not a ceiling.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
public object IosCacheFileProtection {

    private const val DIRECTORY_NAME = "OpenCodeMobileCache"

    private val companionSuffixes: List<String> = listOf("-wal", "-shm", "-journal")

    /** The dedicated directory that holds the cache DB. */
    public fun cacheDirectoryPath(): String {
        val base = NSSearchPathForDirectoriesInDomains(
            NSApplicationSupportDirectory,
            NSUserDomainMask,
            true,
        ).firstOrNull() as? String ?: NSTemporaryDirectory()
        return "$base/$DIRECTORY_NAME"
    }

    /**
     * Creates the cache directory (if needed) with
     * [NSFileProtectionCompleteUnlessOpen] and excludes it — and therefore its
     * contents — from backup, then returns its path.
     */
    public fun ensureProtectedCacheDirectory(): String {
        val directory = cacheDirectoryPath()
        NSFileManager.defaultManager.createDirectoryAtPath(
            path = directory,
            withIntermediateDirectories = true,
            attributes = protectionAttributes(),
            error = null,
        )
        applyProtection(directory)
        excludeFromBackup(directory)
        return directory
    }

    /**
     * Applies [NSFileProtectionCompleteUnlessOpen] to the DB and its companions
     * and excludes them from backup. Safe to call after every open because
     * SQLite may recreate `-wal`/`-shm`.
     */
    public fun protectCacheFiles(directory: String = cacheDirectoryPath()) {
        applyProtection(directory)
        excludeFromBackup(directory)
        cacheFilePaths(directory).forEach { path ->
            if (NSFileManager.defaultManager.fileExistsAtPath(path)) {
                applyProtection(path)
                excludeFromBackup(path)
            }
        }
    }

    /** The DB file plus its `-wal`, `-shm`, and `-journal` companions. */
    public fun cacheFilePaths(directory: String = cacheDirectoryPath()): List<String> {
        val database = "$directory/$CACHE_DATABASE_NAME"
        return listOf(database) + companionSuffixes.map { database + it }
    }

    /** Whether [path] carries `NSURLIsExcludedFromBackupKey`. */
    public fun isExcludedFromBackup(path: String): Boolean {
        val url = NSURL.fileURLWithPath(path)
        return memScoped {
            val value = alloc<ObjCObjectVar<Any?>>()
            val ok = url.getResourceValue(
                value.ptr,
                forKey = NSURLIsExcludedFromBackupKey,
                error = null,
            )
            ok && (value.value as? Boolean) == true
        }
    }

    /** The `NSFileProtectionKey` class of [path], or null when unset. */
    public fun protectionClass(path: String): String? =
        NSFileManager.defaultManager.attributesOfItemAtPath(path, null)
            ?.get(NSFileProtectionKey) as? String

    private fun applyProtection(path: String) {
        NSFileManager.defaultManager.setAttributes(
            attributes = protectionAttributes(),
            ofItemAtPath = path,
            error = null,
        )
    }

    private fun excludeFromBackup(path: String) {
        val url = NSURL.fileURLWithPath(path)
        url.setResourceValue(true, forKey = NSURLIsExcludedFromBackupKey, error = null)
    }

    private fun protectionAttributes(): Map<Any?, Any?> =
        mapOf<Any?, Any?>(NSFileProtectionKey to NSFileProtectionCompleteUnlessOpen)
}
