package org.opencodemobile.shared.domain.permission

/**
 * Port over the server's permission surface (`GET /permission`,
 * `POST /permission/{requestID}/reply`).
 *
 * Implemented in `shared/networking`, the only layer allowed to know the wire
 * encoding. [reply] takes the app's [PermissionDecision]; the adapter maps it to
 * the exact server value (`once` / `reject` / `always`) and must refuse to send
 * a decision the request never exposed (defence in depth: the coordinator is the
 * primary enforcement point).
 */
public interface PermissionPort {
    /**
     * Lists the requests the server currently considers pending.
     *
     * This is the authoritative reconciliation surface: a request decided while
     * the app was killed or offline is absent here and must not be re-surfaced.
     */
    public suspend fun pendingPermissions(): List<PermissionRequest>

    /**
     * Sends [decision] for [requestId] to the server, at most once (D9: no
     * queue, no retry, no replay). Throws on a transport failure.
     *
     * The content binding required by `docs/ARCHITECTURE.md` is enforced
     * client-side by the coordinator before this call: the pinned v2 reply body
     * has no field for a content hash, so the server cannot re-verify it. See
     * `docs/ARCHITECTURE.md` §"Permission approval confirmation".
     */
    public suspend fun reply(
        requestId: String,
        decision: PermissionDecision,
    ): PermissionReplyOutcome
}

/** Outcome of a single [PermissionPort.reply] call. */
public sealed interface PermissionReplyOutcome {
    /** The server accepted the decision. */
    public data object Accepted : PermissionReplyOutcome

    /**
     * The server refused the decision (for example the request is gone or the
     * decision is not one it exposes). [reason] is safe to show.
     */
    public data class Rejected(public val reason: String) : PermissionReplyOutcome
}

/**
 * Durable store for the currently pending requests (V1-06: a pending permission
 * survives an app kill).
 *
 * It is a **cache of server state**, never a second source of truth: on start the
 * app first renders what it stored, then reconciles against
 * [PermissionPort.pendingPermissions]. Storing a request is not approving it, and
 * nothing in this port can send a decision.
 */
public interface PendingPermissionStore {
    /** The last persisted pending requests, in arrival order. */
    public suspend fun load(): List<PermissionRequest>

    /** Replaces the persisted set with [requests] (empty clears it). */
    public suspend fun save(requests: List<PermissionRequest>)
}

/**
 * A decoded realtime event that affects the pending-permission set.
 *
 * The event pipeline delivers raw payloads; `shared/networking` decodes them into
 * these domain values so the application layer never parses JSON.
 */
public sealed interface PermissionEvent {
    /** The server asked for a decision (`permission.asked`). */
    public data class Asked(public val request: PermissionRequest) : PermissionEvent

    /**
     * The server reports the request was answered, possibly from another surface
     * (`permission.replied`). It must be removed from the pending set.
     */
    public data class Replied(public val requestId: String) : PermissionEvent
}

/**
 * Decodes a raw realtime event into a [PermissionEvent], or null when the event
 * is unrelated. Implemented in `shared/networking`.
 */
public interface PermissionEventDecoder {
    /** Never throws: an undecodable payload is reported as null and dropped. */
    public fun decode(type: String, payload: String): PermissionEvent?
}

/**
 * Posts the local notification that signals a pending permission request (OP4).
 *
 * The notification is a **state signal only**: it carries the notification plan
 * built by [PermissionPolicy.notificationFor], which has no approve action and
 * whose tap target is the in-app confirmation screen. Implementations must never
 * add an approval action or an approving deep link.
 */
public interface PermissionNotifier {
    /** Called after the pending set changes. [pending] is the new, full set. */
    public suspend fun onPendingChanged(pending: List<PermissionRequest>)
}

/** Default [PermissionNotifier] for platforms without a notification implementation. */
public object NoOpPermissionNotifier : PermissionNotifier {
    override suspend fun onPendingChanged(pending: List<PermissionRequest>): Unit = Unit
}
