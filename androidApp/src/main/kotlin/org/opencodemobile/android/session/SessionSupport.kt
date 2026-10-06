package org.opencodemobile.android.session

import org.opencodemobile.shared.domain.session.SessionCapabilities
import org.opencodemobile.shared.domain.session.SessionGateway
import org.opencodemobile.shared.domain.session.SessionNotConnectedException
import org.opencodemobile.shared.domain.session.SessionSummary

/**
 * The active server connection the V1-04 session surface is scoped to.
 *
 * The app shell has no connection/onboarding composition root yet, so this seam
 * lets the sessions graph be assembled today against an **optional** connection:
 * the connection composition root binds a `SessionConnection` once it exists,
 * and `sessionsModule` falls back to a fail-closed, offline surface until then.
 * It mirrors the `PermissionConnection` / `CacheConnection` seams and should be
 * unified with them when the connection root lands.
 *
 * `gateway` is the real `OpenCodeV2Adapter`; the seam never re-implements
 * networking.
 */
public interface SessionConnection {
    /** The real `OpenCodeV2Adapter` session surface. */
    public val gateway: SessionGateway

    /** The server profile the cache is scoped to. */
    public val serverId: String

    /** The single project V1 opens, per OP3. */
    public val projectId: String
}

/**
 * Fail-closed [SessionGateway] used while no connection is wired.
 *
 * It throws rather than returning an empty list: an empty list would render an
 * empty session screen, hiding the real cause. The controller's offline gate
 * keeps this port from being reached on the list path; a wiring mistake still
 * fails closed instead of pretending the server has no sessions.
 */
internal object UnavailableSessionGateway : SessionGateway {
    override suspend fun listSessions(directory: String?): List<SessionSummary> =
        throw SessionNotConnectedException()

    override suspend fun getSession(sessionId: String): SessionSummary =
        throw SessionNotConnectedException()

    override suspend fun createSession(directory: String?, title: String?): SessionSummary =
        throw SessionNotConnectedException()

    override suspend fun renameSession(sessionId: String, title: String): SessionSummary =
        throw SessionNotConnectedException()

    override suspend fun deleteSession(sessionId: String): Unit =
        throw SessionNotConnectedException()

    override suspend fun forkSession(sessionId: String): SessionSummary =
        throw SessionNotConnectedException()

    override suspend fun sessionCapabilities(directory: String?): SessionCapabilities =
        SessionCapabilities.Unknown
}

/**
 * A [SessionGateway] that resolves the active [SessionConnection] **at call
 * time**, so a connection root that registers the seam after the Koin graph was
 * first forced still lets the list reach the server.
 */
internal class DeferredSessionGateway(
    private val resolveConnection: () -> SessionConnection?,
) : SessionGateway {
    private fun gateway(): SessionGateway = resolveConnection()?.gateway ?: UnavailableSessionGateway

    override suspend fun listSessions(directory: String?): List<SessionSummary> =
        gateway().listSessions(directory)

    override suspend fun getSession(sessionId: String): SessionSummary =
        gateway().getSession(sessionId)

    override suspend fun createSession(directory: String?, title: String?): SessionSummary =
        gateway().createSession(directory, title)

    override suspend fun renameSession(sessionId: String, title: String): SessionSummary =
        gateway().renameSession(sessionId, title)

    override suspend fun deleteSession(sessionId: String): Unit =
        gateway().deleteSession(sessionId)

    override suspend fun forkSession(sessionId: String): SessionSummary =
        gateway().forkSession(sessionId)

    override suspend fun sessionCapabilities(directory: String?): SessionCapabilities =
        gateway().sessionCapabilities(directory)
}
