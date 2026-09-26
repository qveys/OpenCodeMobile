package org.opencodemobile.shared.domain.connection

/**
 * Outcome of checking the identity of the server a profile points at.
 *
 * The four states are the whole decision surface for T1
 * (`docs/ARCHITECTURE.md` §"Server identity verification (T1)"):
 * a pinned match is [Trusted]; a first-ever contact is [FirstContact] and must
 * be explicitly confirmed before the credential is sent; any divergence from a
 * previously pinned value is [Changed] and fails closed; a profile with no TLS
 * has no certifiable identity and is [PlaintextHttp].
 */
public sealed interface ServerIdentityCheck {
    /** Presented SPKI matched the pinned fingerprint. */
    public data class Trusted(
        public val pinned: ServerFingerprint,
    ) : ServerIdentityCheck

    /** No fingerprint is pinned yet; [presented] must be shown and confirmed. */
    public data class FirstContact(
        public val presented: ServerFingerprint,
    ) : ServerIdentityCheck

    /**
     * [presented] differs from the pinned [previous]. This is the blocking
     * "server identity changed" state: no request carrying the credential may
     * be sent until the user explicitly re-confirms (functionally a new TOFU
     * acceptance).
     */
    public data class Changed(
        public val previous: ServerFingerprint,
        public val presented: ServerFingerprint,
    ) : ServerIdentityCheck

    /**
     * The profile has no TLS, so there is no certificate to pin. The app cannot
     * verify the server's identity or protect the credential in transit; a
     * persistent textual warning must be shown whenever the profile is used.
     */
    public data object PlaintextHttp : ServerIdentityCheck
}

/**
 * Typed failures raised by the TOFU identity layer. Callers (the adapter, the
 * connection UI) branch on these instead of on message strings.
 */
public sealed class ServerIdentityException(message: String) : IllegalStateException(message) {
    /** A first contact was never confirmed, so no credential may be attached. */
    public class ConfirmationRequired(public val presented: ServerFingerprint) :
        ServerIdentityException("Server identity was not confirmed; refusing to send the credential")

    /** The presented identity changed and no explicit re-confirmation was given. */
    public class IdentityChanged(
        public val previous: ServerFingerprint,
        public val presented: ServerFingerprint,
    ) : ServerIdentityException(
        "Server identity changed (pinned ${previous.colonSeparated}, presented ${presented.colonSeparated}); " +
            "refusing to send the credential",
    )

    /** The TLS handshake completed without yielding a leaf certificate. */
    public class NoCertificatePresented :
        ServerIdentityException("The TLS handshake presented no leaf certificate to verify")
}