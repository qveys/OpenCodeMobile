package org.opencodemobile.shared.persistence.cache

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Name of the local cache database file (no path). The Android backup-exclusion
 * rules and the iOS backup-exclusion helper both key off this name; keep them in
 * sync with [CACHE_DATABASE_NAME].
 */
public const val CACHE_DATABASE_NAME: String = "opencodemobile_cache.db"

/**
 * Owns the encrypted cache [SqlDriver] and the key-loss recovery rule.
 *
 * Key loss is a **cache miss**, never a fatal error
 * (`docs/ARCHITECTURE.md` §"Local cache encryption at rest"): if the driver can
 * not be opened — a missing or invalidated Keystore key, or a file encrypted
 * under a different key — the disposable cache is wiped and reopened empty, then
 * rebuilt from the next server snapshot. It never asks the user for anything.
 */
public class CacheDatabase(
    private val provider: CacheDriverProvider,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val openLock = Mutex()
    private var driver: SqlDriver? = null
    private var cache: SqlSessionCache? = null

    /** Returns the open cache, creating it on first use. */
    public suspend fun open(): SqlSessionCache = openLock.withLock {
        cache ?: openLocked()
    }

    /**
     * Wipes the local cache and returns a fresh, empty one. Used on key loss and
     * when a profile is removed.
     */
    public suspend fun wipeAndRebuild(): SqlSessionCache = openLock.withLock {
        closeLocked()
        provider.deleteLocalCache()
        openLocked()
    }

    /** Closes the driver. The cache can be reopened later. */
    public suspend fun close() {
        openLock.withLock { closeLocked() }
    }

    private suspend fun openLocked(): SqlSessionCache {
        val opened = try {
            provider.createDriver()
        } catch (first: Throwable) {
            // The stored key no longer matches the file (invalidated Keystore
            // entry, restored app data, …). The cache is disposable: wipe and
            // rebuild instead of surfacing an error to the user.
            provider.deleteLocalCache()
            provider.createDriver()
        }
        driver = opened
        return SqlSessionCache(opened, ioDispatcher).also { cache = it }
    }

    private fun closeLocked() {
        cache = null
        driver?.let { runCatching { it.close() } }
        driver = null
    }
}
