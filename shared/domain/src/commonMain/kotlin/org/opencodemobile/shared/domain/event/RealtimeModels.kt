package org.opencodemobile.shared.domain.event

/**
 * State of the realtime pipeline (`docs/ARCHITECTURE.md` §3.2).
 *
 * The pipeline is owned by a single `EventProcessor` per connection. It always
 * starts by reconciling a server snapshot and only then goes live, so a consumer
 * that observes [Live] is guaranteed to be looking at a reconciled state.
 */
public enum class ConnectionPhase {
    /** No pipeline is running. */
    Idle,

    /** A server snapshot is being fetched to seed (or re-seed) reconciliation. */
    Reconciling,

    /** The SSE stream is open; events are applied live on top of the snapshot. */
    Live,

    /**
     * The SSE stream ended early, was idle past the configured timeout, or failed.
     * The pipeline is reconciling through the mandatory polling fallback
     * (`GET /session/status`) with bounded exponential backoff until the transport
     * becomes healthy again.
     */
    Polling,

    /** The pipeline was stopped by its owner. */
    Stopped,
}

/** One session as reported by the server snapshot (`GET /session`). */
public data class SessionSnapshot(
    public val id: String,
    public val title: String?,
    public val directory: String?,
    public val updatedAt: Long?,
)

/**
 * Server-reported status of one session (`GET /session/status`).
 *
 * The shape mirrors the pinned spec: `type` is `idle`, `busy` or `retry`, and the
 * `retry` case additionally carries `attempt` / `message` / `next`. Reconciliation
 * must never lose the `retry` state (it is what the session list renders).
 */
public data class SessionStatus(
    public val type: String,
    public val attempt: Int? = null,
    public val message: String? = null,
    public val next: Long? = null,
)

/**
 * The server snapshot that seeds reconciliation on every (re)connect.
 *
 * Per D2 the server wins every conflict: the pipeline replaces its local view of
 * [sessions] and [statuses] with this snapshot. No local state survives a
 * reconciliation.
 */
public data class RealtimeSnapshot(
    public val sessions: List<SessionSnapshot>,
    public val statuses: Map<String, SessionStatus>,
)

/**
 * One SSE record as read off the wire, before normalization.
 *
 * [id] is the server-issued event id (`id:` field) or `null` when the server did not
 * send one. [data] is the raw `data:` payload, which the pipeline validates as JSON
 * before accepting it. [type] is the `event:` field, defaulting to `message`.
 *
 * Transport code (SSE framing) produces these; it never interprets the payload.
 */
public data class RawServerEvent(
    public val id: String?,
    public val type: String,
    public val data: String,
)

/**
 * One normalized event, ordered by the pipeline and safe to apply to the live model.
 *
 * [sequence] is assigned by the pipeline and is strictly increasing across a
 * connection lifetime. [id] is the server-issued id used to drop duplicates and
 * replayed events. [payload] is the raw `data:` JSON: this layer does not depend on a
 * JSON model, so typed parsing stays in the application layer.
 */
public data class ServerEvent(
    public val sequence: Long,
    public val id: String?,
    public val type: String,
    public val payload: String,
)

/**
 * The reconciled in-memory model exposed to consumers (source for Compose state).
 *
 * It holds the last server snapshot and its statuses. It is deliberately not a cache:
 * it can be discarded and rebuilt from the next snapshot at any time.
 */
public data class RealtimeState(
    public val phase: ConnectionPhase = ConnectionPhase.Idle,
    public val snapshot: RealtimeSnapshot? = null,
    public val sessions: List<SessionSnapshot> = emptyList(),
    public val statuses: Map<String, SessionStatus> = emptyMap(),
    /** Number of normalized events accepted since the pipeline started. */
    public val eventCount: Long = 0L,
    /** Server-issued id of the most recently accepted event, when it had one. */
    public val lastEventId: String? = null,
)
