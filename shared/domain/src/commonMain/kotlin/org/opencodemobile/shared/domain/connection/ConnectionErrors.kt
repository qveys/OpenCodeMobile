package org.opencodemobile.shared.domain.connection

/**
 * Typed failures raised while establishing a connection to an OpenCode Server
 * (`docs/ARCHITECTURE.md` §3.1). They are deliberately separate from
 * [ServerIdentityException] (T1): identity failures and handshake failures are
 * raised at different stages and the connection feature renders a different
 * screen for each.
 */
public sealed class HandshakeException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    /**
     * The health probe (`GET /global/health`) could not be completed because of
     * a transport or HTTP failure. The server is unreachable, not incompatible.
     */
    public class HealthUnavailable(failure: Throwable) :
        HandshakeException("The server health check could not be completed", failure)

    /** The health probe answered but reported the server as unhealthy. */
    public class ServerUnhealthy :
        HandshakeException("The server reported itself unhealthy; refusing to continue the handshake")

    /**
     * The health probe answered but the version was missing, blank, or not a
     * parseable `major.minor.patch` string, so compatibility cannot be
     * established.
     */
    public class Incomplete(public val detail: String) :
        HandshakeException("Incomplete handshake: $detail")

    /** The reported server version is outside [profile]'s supported range. */
    public class Incompatible(
        public val serverVersion: ServerVersion,
        public val profile: CompatibilityProfile,
    ) : HandshakeException(
        "Incompatible server version $serverVersion; supported: ${profile.supportedRange}",
    )
}

/**
 * The profile was rejected by [HttpConnectionPolicy] before any request was
 * built, so no credential could be sent.
 */
public sealed class ConnectionPolicyException(message: String) : IllegalStateException(message) {
    /** Plaintext HTTP to a public host; TLS is mandatory for external connections. */
    public class Rejected(
        public val decision: HttpConnectionPolicyDecision.Rejected,
    ) : ConnectionPolicyException(
        "Connection policy rejected the profile: ${decision.violation} (scope ${decision.scope})",
    )

    /**
     * A request used an HTTP method outside
     * [HttpConnectionPolicy.allowedMethods]. Refused before it reaches the
     * network.
     */
    public class MethodNotAllowed(public val method: String) : ConnectionPolicyException(
        "HTTP method $method is not allowed by the connection policy " +
            "(allowed: ${HttpConnectionPolicy.allowedMethods.joinToString()})",
    )
}
