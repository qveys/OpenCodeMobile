package org.opencodemobile.shared.domain.session

/**
 * The port for the V1-04 session surface, implemented by `OpenCodeV2Adapter` in
 * `shared/networking` (the only module allowed to touch the generated client,
 * Rule R3).
 *
 * It is deliberately separate from
 * [org.opencodemobile.shared.domain.connection.OpenCodeGateway] (connection
 * lifecycle) and from
 * [org.opencodemobile.shared.domain.interaction.OpenCodeInteractionGateway]
 * (per-turn interaction): a feature depends on exactly the surface it needs.
 *
 * **Mutations and offline.** Every method throws on failure; the application
 * controller decides what is offered. The controller must consult
 * [org.opencodemobile.shared.domain.cache.MutationGate] before calling a
 * mutating method, because the cache is read-only offline (D8).
 *
 * **[directory] scoping.** The list/create calls carry the active project root
 * so a session from another workspace on the same server cannot leak in
 * (ADR-0002 §3.3).
 */
public interface SessionGateway {
    /** Lists the sessions the server exposes (`GET /api/session`), newest activity first.
     *
     * The server paginates by cursor; the adapter follows the cursor internally
     * and returns the full list, so the port stays cursor-free. */
    public suspend fun listSessions(directory: String? = null): List<SessionSummary>

    /** Reads one session (`GET /api/session/{id}`), used by "resume"/open. */
    public suspend fun getSession(sessionId: String): SessionSummary

    /** Creates a session (`POST /api/session`). [title] may be null to let the server name it. */
    public suspend fun createSession(directory: String? = null, title: String? = null): SessionSummary

    /** Renames a session (`PATCH /api/session/{id}`). [title] must be normalized by the caller. */
    public suspend fun renameSession(sessionId: String, title: String): SessionSummary

    /** Deletes a session (`DELETE /api/session/{id}`). */
    public suspend fun deleteSession(sessionId: String)

    /** Forks a session (`POST /api/session/{id}/fork`). */
    public suspend fun forkSession(sessionId: String): SessionSummary
}
