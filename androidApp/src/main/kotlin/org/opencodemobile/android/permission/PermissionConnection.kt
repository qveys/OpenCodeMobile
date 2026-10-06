package org.opencodemobile.android.permission

import org.opencodemobile.shared.domain.event.EventSource
import org.opencodemobile.shared.domain.permission.PermissionEventDecoder
import org.opencodemobile.shared.domain.permission.PermissionPort

/**
 * The active server connection the permission surface is scoped to (OPE-173).
 *
 * This is the seam the L2/L3 app shell binds once a connection exists: `port` is
 * the real `OpenCodePermissionGateway`, `decoder` is the same gateway, and
 * `source` is the started `EventProcessor`. It never re-implements networking.
 *
 * S10a declares the seam here because [LiveConnection] exposes the
 * `permission` view; S10b (#62) owns the permission module, the fail-closed
 * fallbacks and the Koin wiring that consume it.
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
