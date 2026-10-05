package org.opencodemobile.shared.domain.connection

/**
 * The port the rest of the app uses to talk to an OpenCode Server v2 instance.
 * Implemented by `OpenCodeV2Adapter` in `shared/networking`, which is the only
 * code allowed to touch the generated OpenAPI client (Rule R3 / ADR-0002).
 *
 * [connect] is the T1 gate: it must complete identity verification before the
 * credential is attached to any request.
 */
public interface OpenCodeGateway {
    /**
     * Performs the connection handshake against [profile]
     * (`docs/ARCHITECTURE.md` §3.1):
     *
     * 1. [HttpConnectionPolicy] is evaluated first; a rejected profile throws
     *    [ConnectionPolicyException.Rejected] before any request is built.
     * 2. [ServerIdentityCheck] is verified. Implementations must throw
     *    [ServerIdentityException] instead of sending the credential when the
     *    identity is [ServerIdentityCheck.FirstContact] or
     *    [ServerIdentityCheck.Changed].
     * 3. The credential is released to the transport only after the identity
     *    check has passed (or the profile is plaintext HTTP on an allowed
     *    scope, which is surfaced with a warning).
     * 4. `GET /global/health` is read and its version is gated by the
     *    [CompatibilityProfile]; an unreachable, unhealthy, incomplete, or
     *    incompatible server throws [HandshakeException].
     */
    public suspend fun connect(profile: ServerProfile, credential: ServerCredential?): ConnectionHandshake

    /** Tears down the current connection, if any, and drops the credential permit. */
    public fun disconnect()
}

/**
 * A server credential. Kept as a small value type so it can be handed to the
 * adapter without ever being formatted into logs.
 */
public class ServerCredential(
    public val bearerToken: String,
) {
    init {
        require(bearerToken.isNotBlank()) { "ServerCredential.bearerToken must not be blank" }
    }

    /** Never render the secret; logs and crash reports must not contain it (T4). */
    override fun toString(): String = "ServerCredential(***)"
}

/** Result of `GET /global/health`. */
public data class ServerHealth(
    public val healthy: Boolean,
    public val version: String?,
)

/** Result of a successful connection handshake. */
public data class ConnectionHandshake(
    public val profileId: String,
    public val health: ServerHealth,
    public val identity: ServerIdentityCheck,
    /** The parsed server version that passed the [CompatibilityProfile] gate. */
    public val version: ServerVersion,
    /** Where the profile's host sits on the network path, per [HttpConnectionPolicy]. */
    public val scope: ServerNetworkScope,
)
