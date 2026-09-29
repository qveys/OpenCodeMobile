package org.opencodemobile.shared.persistence.cache

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.opencodemobile.shared.domain.cache.CacheUnavailableException
import org.opencodemobile.shared.domain.cache.GatedSessionCacheWriter
import org.opencodemobile.shared.domain.cache.MutationGate
import org.opencodemobile.shared.domain.cache.SessionCache
import org.opencodemobile.shared.domain.cache.SessionCacheWriter

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
 *
 * Two failure modes are deliberately distinguished:
 *
 * - **Cancellation** is never treated as key loss: a coroutine cancelled while
 *   opening the cache must not destroy the local data ([CancellationException]
 *   is rethrown before the destructive branch).
 * - A **second** failure, or a wipe that did not actually remove the files, is
 *   surfaced as [CacheUnavailableException] so the application can render an
 *   empty, read-only state instead of recreating over an undecryptable file.
 *
 * The object graph the app composes never sees the raw [SqlSessionCache]: reads
 * are handed out as the [SessionCache] port ([open]) and writes only through
 * [writer], which is already wrapped in a
 * [org.opencodemobile.shared.domain.cache.GatedSessionCacheWriter] so the D8
 * offline read-only rule cannot be bypassed (OPE-172).
 */
public class CacheDatabase(
    private val provider: CacheDriverProvider,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val openLock = Mutex()
    private var driver: SqlDriver? = null
    private var cache: SqlSessionCache? = null

    /** Returns the open cache read port, creating the database on first use. */
    public suspend fun open(): SessionCache = openSqlCache()

    /**
     * Returns the cache write port, already wrapped with the D8 gate.
     *
     * This is the **only** way to obtain a [SessionCacheWriter]. The returned
     * writer refuses every `put*` (and [SessionCacheWriter.wipeServer]) with
     * [org.opencodemobile.shared.domain.cache.CacheMutationNotAllowedException]
     * while [gate] reports mutations are disabled, so an offline caller fails
     * fast and nothing is queued.
     */
    public suspend fun writer(gate: MutationGate): SessionCacheWriter =
        GatedSessionCacheWriter(gate, openSqlCache())

    /**
     * Wipes the local cache and returns a fresh, empty read port. Used on key
     * loss and when a profile is removed.
     *
     * Recovery from key loss must work offline, so this path is deliberately not
     * behind the mutation gate; it returns only the read port, so it can never
     * leak an ungated writer.
     */
    public suspend fun wipeAndRebuild(): SessionCache = openLock.withLock {
        closeLocked()
        deleteLocalCacheOrThrow(cause = null)
        openLocked()
    }

    /** Closes the driver. The cache can be reopened later. */
    public suspend fun close() {
        openLock.withLock { closeLocked() }
    }

    private suspend fun openSqlCache(): SqlSessionCache = openLock.withLock {
        cache ?: openLocked()
    }

    private suspend fun openLocked(): SqlSessionCache {
        val opened = try {
            provider.createDriver()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (first: Throwable) {
            // The stored key no longer matches the file (invalidated Keystore
            // entry, restored app data, …). The cache is disposable: wipe and
            // rebuild instead of surfacing an error to the user.
            deleteLocalCacheOrThrow(cause = first)
            try {
                provider.createDriver()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (second: Throwable) {
                second.addSuppressed(first)
                throw CacheUnavailableException(
                    "The local cache could not be rebuilt after a wipe",
                    second,
                )
            }
        }
        driver = opened
        return SqlSessionCache(opened, ioDispatcher).also { cache = it }
    }

    private suspend fun deleteLocalCacheOrThrow(cause: Throwable?) {
        val deleted = try {
            provider.deleteLocalCache()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            cause?.let(failure::addSuppressed)
            throw CacheUnavailableException("Could not delete the local cache", failure)
        }
        if (!deleted) {
            throw CacheUnavailableException(
                "Could not fully delete the local cache; refusing to reopen over it",
                cause,
            )
        }
    }

    private fun closeLocked() {
        cache = null
        driver?.let { runCatching { it.close() } }
        driver = null
    }
}
