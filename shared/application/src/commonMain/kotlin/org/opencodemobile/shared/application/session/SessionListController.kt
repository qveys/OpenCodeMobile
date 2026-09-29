package org.opencodemobile.shared.application.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.opencodemobile.shared.domain.cache.CachedSession
import org.opencodemobile.shared.domain.cache.MutationGate
import org.opencodemobile.shared.domain.cache.SessionCache
import org.opencodemobile.shared.domain.session.ServerUnavailableException
import org.opencodemobile.shared.domain.session.SessionFailure
import org.opencodemobile.shared.domain.session.SessionGateway
import org.opencodemobile.shared.domain.session.SessionMutationNotAllowedException
import org.opencodemobile.shared.domain.session.SessionNotFoundException
import org.opencodemobile.shared.domain.session.SessionRejectedException
import org.opencodemobile.shared.domain.session.SessionSummary
import org.opencodemobile.shared.domain.session.SessionTitlePolicy

/**
 * The cache scope the list reads offline (D8; `docs/ARCHITECTURE.md` §3.4).
 * Kept in the application layer because it is a plain pair of ids, not a
 * platform concern.
 */
public data class SessionsScope(
    public val serverId: String,
    public val projectId: String,
)

/** Where the rendered rows came from. Shown as an explicit technical state. */
public enum class SessionListSource {
    /** Fresh from the server (`GET /session`). */
    Live,

    /** Rebuilt from the encrypted cache, in read-only mode. */
    Cache,
}

/** The actionable failure the list shows instead of an empty screen. */
public sealed class SessionListError {
    /** Server unreachable / 5xx / no connection: tells the user to check the connection. */
    public data object ServerUnavailable : SessionListError()

    /** Offline and a mutation was attempted: the list is read-only (D8). */
    public data object Offline : SessionListError()

    /** The session no longer exists on the server. */
    public data class NotFound(public val sessionId: String) : SessionListError()

    /** The server refused the operation, or the local input was invalid. */
    public data class Rejected(public val message: String) : SessionListError()
}

/**
 * The session list state observed by the Compose layer.
 *
 * [sessions] is what the list renders. [source] and [offline] are explicit
 * technical states (design system §"etats techniques explicites"), and
 * [forkAvailable] comes from the server surface, never from a local constant.
 */
public data class SessionListState(
    public val loading: Boolean = false,
    public val sessions: List<SessionSummary> = emptyList(),
    public val source: SessionListSource = SessionListSource.Live,
    public val offline: Boolean = false,
    public val forkAvailable: Boolean = false,
    public val error: SessionListError? = null,
    /** Session id with an in-flight mutation, so the row can show a busy state. */
    public val pendingSessionId: String? = null,
    /** A short confirmation of the last successful mutation. */
    public val notice: String? = null,
    /** True when the connection is live and the server surface was read. */
    public val online: Boolean = false,
)

/**
 * The V1-04 session list surface: list, create, open, rename, delete, fork.
 *
 * It owns the D8 read/write split:
 *
 * - **Reads** always render something: online from the server, offline (or on a
 *   server/network failure) from the encrypted cache, in read-only mode.
 * - **Mutations** are refused before touching the wire when
 *   [MutationGate.mutationsAllowed] is false, and the fork action is only
 *   offered when the server's published surface says it exists.
 *
 * It never optimistically writes the cache: after a successful mutation it
 * reloads the authoritative list from the server. The cache stays a
 * server-event projection (D8), never a source of truth.
 */
public class SessionListController(
    private val gateway: SessionGateway,
    private val cache: SessionCache,
    private val gate: MutationGate,
    private val scope: () -> SessionsScope?,
    /** Active project root carried as the `directory` parameter, or null. */
    private val directory: String? = null,
) {
    private val mutableState = MutableStateFlow(SessionListState())

    /** The state the Compose layer observes (unidirectional data flow). */
    public val state: StateFlow<SessionListState> = mutableState.asStateFlow()

    /**
     * Loads the list.
     *
     * Online it reads the server and then the capability surface; on any failure
     * it falls back to the cache and surfaces an actionable [SessionListError].
     * Offline it reads the cache directly and marks the state read-only. It
     * never throws: an empty cache renders an empty list with the reason.
     */
    public suspend fun refresh() {
        mutableState.update { it.copy(loading = true, error = null, notice = null) }

        if (!gate.mutationsAllowed()) {
            val cached = cachedSessions()
            mutableState.update {
                it.copy(
                    loading = false,
                    sessions = cached,
                    source = SessionListSource.Cache,
                    offline = true,
                    online = false,
                    forkAvailable = false,
                    error = null,
                )
            }
            return
        }

        try {
            val sessions = gateway.listSessions(directory)
            val capabilities = gateway.sessionCapabilities(directory)
            mutableState.update {
                it.copy(
                    loading = false,
                    sessions = sessions.sortedByDescending { session -> session.updatedAt },
                    source = SessionListSource.Live,
                    offline = false,
                    online = true,
                    forkAvailable = capabilities.forkAvailable,
                    error = null,
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            val cached = cachedSessions()
            mutableState.update {
                it.copy(
                    loading = false,
                    sessions = cached,
                    source = SessionListSource.Cache,
                    offline = false,
                    online = false,
                    forkAvailable = false,
                    error = failure.toListError(),
                )
            }
        }
    }

    /** Clears the last error/notice after the UI has shown it. */
    public fun clearMessage() {
        mutableState.update { it.copy(error = null, notice = null) }
    }

    /** Creates a session and reloads the authoritative list (`POST /session`). */
    public suspend fun createSession(title: String? = null) {
        if (title != null && !SessionTitlePolicy.isValid(title)) {
            mutableState.update {
                it.copy(
                    error = SessionListError.Rejected(
                        "A title must be 1..${SessionTitlePolicy.MAX_LENGTH} characters",
                    ),
                )
            }
            return
        }
        val normalized = title?.let { SessionTitlePolicy.normalize(it) }
        mutate(pendingSessionId = null, notice = "Session created") {
            gateway.createSession(directory = directory, title = normalized)
            Unit
        }
    }

    /**
     * Resumes (opens) [sessionId].
     *
     * Online it re-reads the session from the server so a stale row cannot be
     * opened; offline it returns the cached summary, because reading stays
     * available offline (D8). Returns the summary to navigate to, or null when
     * the session cannot be opened.
     */
    public suspend fun openSession(sessionId: String): SessionSummary? {
        val cached = mutableState.value.sessions.firstOrNull { it.id == sessionId }

        if (!gate.mutationsAllowed()) {
            // Reading is allowed offline; opening is a read.
            return cached
        }

        mutableState.update { it.copy(pendingSessionId = sessionId) }
        return try {
            val session = gateway.getSession(sessionId)
            mutableState.update { it.copy(pendingSessionId = null, error = null) }
            session
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            mutableState.update { it.copy(pendingSessionId = null, error = failure.toListError()) }
            null
        }
    }

    /** Renames [sessionId] and reloads the list (`PATCH /session/{id}`). */
    public suspend fun renameSession(sessionId: String, title: String) {
        val normalized = SessionTitlePolicy.normalize(title)
        if (normalized == null || !SessionTitlePolicy.isValid(normalized)) {
            mutableState.update {
                it.copy(
                    error = SessionListError.Rejected(
                        "A title must be 1..${SessionTitlePolicy.MAX_LENGTH} characters",
                    ),
                )
            }
            return
        }
        mutate(pendingSessionId = sessionId, notice = "Session renamed") {
            gateway.renameSession(sessionId, normalized)
            Unit
        }
    }

    /** Deletes [sessionId] and reloads the list (`DELETE /session/{id}`). */
    public suspend fun deleteSession(sessionId: String) {
        mutate(pendingSessionId = sessionId, notice = "Session deleted") {
            gateway.deleteSession(sessionId)
        }
    }

    /**
     * Forks [sessionId] (`POST /session/{id}/fork`).
     *
     * The action is refused locally when the server surface does not expose
     * fork, so no rejected call is ever sent and nothing crashes (V1-04
     * acceptance).
     */
    public suspend fun forkSession(sessionId: String) {
        // Offline read-only (D8) comes first: it is the reason the action is
        // unavailable, and the capability is downgraded to false offline too.
        if (!gate.mutationsAllowed()) {
            mutableState.update { it.copy(error = SessionListError.Offline) }
            return
        }
        if (!mutableState.value.forkAvailable) {
            mutableState.update {
                it.copy(error = SessionListError.Rejected("This server does not expose session forking"))
            }
            return
        }
        mutate(pendingSessionId = sessionId, notice = "Session forked") {
            gateway.forkSession(sessionId)
            Unit
        }
    }

    /**
     * Runs a gated mutation and, on success, reloads the server-authoritative
     * list. Offline this touches no network at all and reports
     * [SessionListError.Offline]; on a server failure the previous list is kept
     * and the failure is surfaced.
     */
    private suspend fun mutate(
        pendingSessionId: String?,
        notice: String,
        block: suspend () -> Unit,
    ) {
        if (!gate.mutationsAllowed()) {
            mutableState.update { it.copy(error = SessionListError.Offline) }
            return
        }

        mutableState.update {
            it.copy(pendingSessionId = pendingSessionId, error = null, notice = null)
        }

        try {
            block()
            val sessions = gateway.listSessions(directory)
            mutableState.update {
                it.copy(
                    pendingSessionId = null,
                    sessions = sessions.sortedByDescending { session -> session.updatedAt },
                    source = SessionListSource.Live,
                    offline = false,
                    online = true,
                    error = null,
                    notice = notice,
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            mutableState.update {
                it.copy(pendingSessionId = null, error = failure.toListError())
            }
        }
    }

    private suspend fun cachedSessions(): List<SessionSummary> {
        val current = scope() ?: return emptyList()
        return cache
            .sessions(current.serverId, current.projectId)
            .map { it.toSummary() }
            .sortedByDescending { session -> session.updatedAt }
    }
}

private fun CachedSession.toSummary(): SessionSummary = SessionSummary(
    id = sessionId,
    title = title?.takeIf { it.isNotBlank() } ?: sessionId,
    projectId = projectId,
    parentSessionId = parentSessionId,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

/** Maps a typed gateway failure to the actionable list error. Unknown -> unavailable. */
private fun Throwable.toListError(): SessionListError = when (this) {
    is SessionMutationNotAllowedException -> SessionListError.Offline
    is SessionNotFoundException -> SessionListError.NotFound(sessionId)
    is SessionRejectedException -> SessionListError.Rejected(message ?: "The server refused the operation")
    is ServerUnavailableException -> SessionListError.ServerUnavailable
    is SessionFailure -> SessionListError.ServerUnavailable
    else -> SessionListError.ServerUnavailable
}
