package org.opencodemobile.android.permission

import org.opencodemobile.shared.domain.permission.PermissionDecision
import org.opencodemobile.shared.domain.permission.PermissionEvent
import org.opencodemobile.shared.domain.permission.PermissionEventDecoder
import org.opencodemobile.shared.domain.permission.PermissionPort
import org.opencodemobile.shared.domain.permission.PermissionReplyOutcome
import org.opencodemobile.shared.domain.permission.PermissionRequest
import org.opencodemobile.shared.domain.permission.PendingPermissionStore

/**
 * Fail-closed [PermissionPort] used while no connection is wired.
 *
 * It deliberately **throws** instead of returning an empty pending list: an empty
 * list would make `reconcile()` clear a persisted pending request. With no
 * connection the coordinator is also offline, so this port is never reached; it
 * exists so a wiring mistake fails closed rather than silently deciding anything.
 */
internal object UnavailablePermissionPort : PermissionPort {
    override suspend fun pendingPermissions(): List<PermissionRequest> =
        throw IllegalStateException("no server connection is wired to the permission surface")

    override suspend fun reply(
        requestId: String,
        decision: PermissionDecision,
    ): PermissionReplyOutcome =
        throw IllegalStateException("no server connection is wired to the permission surface")
}

/** Drops every event while no connection is wired. */
internal object NoOpPermissionEventDecoder : PermissionEventDecoder {
    override fun decode(type: String, payload: String): PermissionEvent? = null
}

/**
 * In-memory [PendingPermissionStore] for the no-connection case.
 *
 * It never persists anything (there is no server to persist for) and it never
 * approves anything: the only path to the server is the [PermissionPort].
 */
internal class InMemoryPendingPermissionStore : PendingPermissionStore {
    private var saved: List<PermissionRequest> = emptyList()

    override suspend fun load(): List<PermissionRequest> = saved

    override suspend fun save(requests: List<PermissionRequest>) {
        saved = requests
    }
}

/**
 * A [PermissionPort] that resolves the active [PermissionConnection] **at call
 * time** instead of at Koin resolution time (N6).
 *
 * `permissionModule`'s graph is forced on the application thread at onCreate. If
 * the connection composition root registers `PermissionConnection` after that
 * (for example through a later `loadKoinModules`), capturing the fallback at
 * resolution time would freeze the surface on [UnavailablePermissionPort] forever,
 * with no error. Resolving per call lets the surface reach the connection as soon
 * as it exists, and still fails closed while it does not.
 */
internal class DeferredPermissionPort(
    private val resolveConnection: () -> PermissionConnection?,
) : PermissionPort {
    override suspend fun pendingPermissions(): List<PermissionRequest> =
        (resolveConnection()?.port ?: UnavailablePermissionPort).pendingPermissions()

    override suspend fun reply(
        requestId: String,
        decision: PermissionDecision,
    ): PermissionReplyOutcome =
        (resolveConnection()?.port ?: UnavailablePermissionPort).reply(requestId, decision)
}

/** A [PermissionEventDecoder] that resolves the active connection at call time (N6). */
internal class DeferredPermissionEventDecoder(
    private val resolveConnection: () -> PermissionConnection?,
) : PermissionEventDecoder {
    override fun decode(type: String, payload: String): PermissionEvent? =
        (resolveConnection()?.decoder ?: NoOpPermissionEventDecoder).decode(type, payload)
}

/**
 * A [PendingPermissionStore] that resolves the connection-backed store lazily on
 * first use (N6), keeping the in-memory fallback until a connection exists.
 */
internal class DeferredPendingPermissionStore(
    private val resolveStore: () -> PendingPermissionStore?,
) : PendingPermissionStore {
    private val fallback: PendingPermissionStore = InMemoryPendingPermissionStore()
    private var resolved: PendingPermissionStore? = null

    private fun store(): PendingPermissionStore {
        resolved?.let { return it }
        return (resolveStore() ?: return fallback).also { resolved = it }
    }

    override suspend fun load(): List<PermissionRequest> = store().load()

    override suspend fun save(requests: List<PermissionRequest>): Unit = store().save(requests)
}
