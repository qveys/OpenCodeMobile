package org.opencodemobile.android.permission

import org.opencodemobile.shared.domain.event.EventSource
import org.opencodemobile.shared.domain.permission.PermissionDecision
import org.opencodemobile.shared.domain.permission.PermissionEvent
import org.opencodemobile.shared.domain.permission.PermissionEventDecoder
import org.opencodemobile.shared.domain.permission.PermissionPort
import org.opencodemobile.shared.domain.permission.PermissionReplyOutcome
import org.opencodemobile.shared.domain.permission.PermissionRequest
import org.opencodemobile.shared.domain.permission.PendingPermissionStore

/**
 * The active server connection the permission surface is scoped to (OPE-173).
 *
 * The app shell has no connection/onboarding composition root yet, so this seam
 * lets the permission graph be assembled today against an **optional** connection:
 * the connection composition root binds a `PermissionConnection` once it exists,
 * and `permissionModule` falls back to a fail-closed, inert surface until then.
 *
 * The wiring never re-implements the networking layer: `port` is the real
 * [org.opencodemobile.shared.networking.permission.OpenCodePermissionGateway],
 * `decoder` is the same gateway, and `source` is the started `EventProcessor`.
 */
public interface PermissionConnection {
    /** The real `GET /permission` + `POST /permission/{id}/reply` port. */
    public val port: PermissionPort

    /** Decodes `permission.asked` / `permission.replied` from the realtime stream. */
    public val decoder: PermissionEventDecoder

    /** The per-connection realtime pipeline the bridge observes. */
    public val source: EventSource

    /** The server profile the pending set is persisted against. */
    public val serverId: String
}

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
