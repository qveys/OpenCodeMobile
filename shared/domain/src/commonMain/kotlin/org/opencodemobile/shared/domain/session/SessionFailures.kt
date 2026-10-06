package org.opencodemobile.shared.domain.session

/**
 * Typed failures of the session surface.
 *
 * These are the only failure types the domain and application layers see for
 * session operations: the adapter maps every HTTP status and transport error to
 * one of them (`docs/ARCHITECTURE.md` §4.3 "Error model"), so a raw status code
 * or a generated error type never escapes `shared/networking`.
 */
public sealed class SessionFailure(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * The server could not be reached at all: connection refused, DNS/TLS failure,
 * timeout, or a 5xx. This is the [ServerUnavailable] case the V1-04 acceptance
 * requires a dedicated, actionable message for — never an empty screen.
 */
public class ServerUnavailableException(
    message: String = "The OpenCode server is unavailable",
    cause: Throwable? = null,
) : SessionFailure(message, cause)

/**
 * No verified connection is active. The generated client releases the
 * credential permit only after the T1 identity check, so there is nothing
 * legitimate to send without a connection.
 */
public class SessionNotConnectedException(
    message: String = "No active OpenCode Server connection",
) : SessionFailure(message)

/** The server answered 404 for the session (already deleted, or a stale row). */
public class SessionNotFoundException(
    public val sessionId: String,
    message: String = "Session not found: $sessionId",
    cause: Throwable? = null,
) : SessionFailure(message, cause)

/** The server refused the request (4xx other than 404). [message] is the reason shown. */
public class SessionRejectedException(
    message: String,
    cause: Throwable? = null,
) : SessionFailure(message, cause)

/**
 * A mutation was attempted while the connection is offline. The cache is
 * read-only offline (D8), so nothing is sent and nothing is queued.
 */
public class SessionMutationNotAllowedException(
    message: String = "Mutations are disabled while offline (D8); the session list is read-only",
) : SessionFailure(message)
