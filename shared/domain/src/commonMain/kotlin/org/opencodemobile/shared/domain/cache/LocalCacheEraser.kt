package org.opencodemobile.shared.domain.cache

/**
 * Erases the local encrypted cache at rest ("Tout effacer", ADR 0009 §2.1.3).
 *
 * The erased scope is the SQLDelight cache database **and** its SQLite sidecar
 * files (`-wal`, `-shm`, `-journal`) plus the cache's at-rest key material. The
 * sidecars matter: a `-wal`/`-shm` residue can still hold row images, so removing
 * only the main database file is not a complete wipe. Removing the key material
 * matters too: without it a stale file cannot be decrypted on the next open.
 *
 * This is the at-rest half of erasure. `shared/application` composes it with the
 * secure-store and notification wipes in
 * [org.opencodemobile.shared.application.erasure.EraseEverythingCoordinator].
 *
 * Implementations must:
 * - be safe to call when no cache exists (absence is not an error);
 * - never leave the database readable under the old key;
 * - never write the erased content, or the key, to a log.
 */
public interface LocalCacheEraser {
    /**
     * Erases the cache database, its sidecars, and its key material.
     *
     * @return true when no cache artifact and no key material remain. A false
     *   result names a residue the caller must surface, not ignore.
     */
    public suspend fun eraseLocalCache(): Boolean
}

/**
 * Fail-closed default for a platform that has no local cache stack wired yet.
 *
 * It reports success because there is genuinely nothing on disk to erase, which
 * is honest for the current iOS build (the iOS cache stack is not composed —
 * see ADR 0008 / OPE-231). When that stack lands, it must bind a real
 * [LocalCacheEraser] instead of this default.
 */
public object EmptyLocalCacheEraser : LocalCacheEraser {
    override suspend fun eraseLocalCache(): Boolean = true
}
