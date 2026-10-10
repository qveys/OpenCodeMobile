package org.opencodemobile.features.connection

import org.opencodemobile.shared.domain.connection.DomainError
import org.opencodemobile.shared.domain.connection.ServerFingerprint
import org.opencodemobile.shared.domain.connection.ServerInputProblem

/** Error category; each maps to a local FR/EN title/message/hint triple. */
public enum class FailureKind {
    UNREACHABLE, UNHEALTHY, HANDSHAKE, INCOMPATIBLE, REJECTED, AUTH, UNCONFIRMED, CHANGED,
    UNVERIFIABLE, POLICY, METHOD, ADDRESS, LINK, STORAGE, UNKNOWN,
}

/**
 * Typed, display-safe connection failure. Carries no free-form server text:
 * only the category, the address [problem] and sanitised fingerprints.
 */
public data class ConnectionFailure(
    public val kind: FailureKind,
    public val problem: ServerInputProblem? = null,
    public val pinned: String? = null,
    public val presented: String? = null,
)

private const val MAX_DISPLAY_LENGTH = 96

/** Drops control, zero-width and bidi-override characters and truncates. */
public fun String.sanitizeForDisplay(): String =
    filterNot { it.isISOControl() || it in BIDI_AND_INVISIBLE }.take(MAX_DISPLAY_LENGTH)

private val BIDI_AND_INVISIBLE: Set<Char> = buildSet {
    addAll('\u200B'..'\u200F')
    addAll('\u202A'..'\u202E')
    addAll('\u2060'..'\u2069')
    add('\u061C')
    add('\uFEFF')
}

private fun ServerFingerprint.display(): String = colonSeparated.sanitizeForDisplay()

/** Maps a [DomainError] to its display-safe model; server-derived text is never copied. */
public fun DomainError.toConnectionFailure(): ConnectionFailure = when (this) {
    is DomainError.Unreachable -> ConnectionFailure(FailureKind.UNREACHABLE)
    is DomainError.ServerUnhealthy -> ConnectionFailure(FailureKind.UNHEALTHY)
    is DomainError.HandshakeIncomplete -> ConnectionFailure(FailureKind.HANDSHAKE)
    is DomainError.ServerIncompatible -> ConnectionFailure(FailureKind.INCOMPATIBLE)
    is DomainError.CredentialRejected -> ConnectionFailure(FailureKind.REJECTED)
    is DomainError.AuthenticationRequired -> ConnectionFailure(FailureKind.AUTH)
    is DomainError.IdentityUnconfirmed -> ConnectionFailure(FailureKind.UNCONFIRMED, presented = presented.display())
    is DomainError.IdentityChanged ->
        ConnectionFailure(FailureKind.CHANGED, pinned = previous.display(), presented = presented.display())
    is DomainError.IdentityNotVerifiable -> ConnectionFailure(FailureKind.UNVERIFIABLE)
    is DomainError.PolicyRejected -> ConnectionFailure(FailureKind.POLICY)
    is DomainError.PolicyMethodNotAllowed -> ConnectionFailure(FailureKind.METHOD)
    is DomainError.InvalidServerAddress -> ConnectionFailure(FailureKind.ADDRESS, problem = problem)
    is DomainError.InvalidImportLink -> ConnectionFailure(FailureKind.LINK)
    is DomainError.StorageFailure -> ConnectionFailure(FailureKind.STORAGE)
    is DomainError.Unknown -> ConnectionFailure(FailureKind.UNKNOWN)
}
