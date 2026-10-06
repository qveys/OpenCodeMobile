package org.opencodemobile.shared.data.permission

import org.opencodemobile.shared.domain.cache.SessionCache
import org.opencodemobile.shared.domain.cache.SessionCacheWriter
import org.opencodemobile.shared.domain.permission.PendingPermissionStore
import org.opencodemobile.shared.domain.permission.PermissionRequest

/**
 * The encrypted-cache [PendingPermissionStore], resolved lazily (OPE-173).
 *
 * The app composition root hands out the D8-gated writer through a suspending
 * accessor (the encrypted database is opened on first use), so the real
 * [CachePendingPermissionStore] delegate is created on the coordinator's coroutine
 * the first time the pending set is read or written.
 *
 * Persisting a request is not approving it: this class can only store the pending
 * set; the only path to the server is
 * `org.opencodemobile.shared.domain.permission.PermissionPort`.
 */
public class DeferredCachePendingPermissionStore(
    private val cache: SessionCache,
    private val writer: suspend () -> SessionCacheWriter,
    private val serverId: String,
) : PendingPermissionStore {

    private var delegate: PendingPermissionStore? = null

    private suspend fun delegate(): PendingPermissionStore {
        delegate?.let { return it }
        return CachePendingPermissionStore(cache, writer(), serverId).also { delegate = it }
    }

    override suspend fun load(): List<PermissionRequest> = delegate().load()

    override suspend fun save(requests: List<PermissionRequest>) {
        delegate().save(requests)
    }
}
