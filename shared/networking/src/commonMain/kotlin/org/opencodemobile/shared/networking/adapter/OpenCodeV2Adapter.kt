package org.opencodemobile.shared.networking.adapter

import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.pluginOrNull
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import org.opencode.mobile.networking.client.generated.apis.OpenCodeApiClient
import org.opencodemobile.shared.domain.connection.CompatibilityProfile
import org.opencodemobile.shared.domain.connection.CompatibilityResult
import org.opencodemobile.shared.domain.connection.ConnectionHandshake
import org.opencodemobile.shared.domain.connection.ConnectionPolicyException
import org.opencodemobile.shared.domain.connection.HandshakeException
import org.opencodemobile.shared.domain.connection.HttpConnectionPolicy
import org.opencodemobile.shared.domain.connection.HttpConnectionPolicyDecision
import org.opencodemobile.shared.domain.connection.OpenCodeGateway
import org.opencodemobile.shared.domain.connection.ServerCredential
import org.opencodemobile.shared.domain.connection.ServerHealth
import org.opencodemobile.shared.domain.connection.ServerIdentityCheck
import org.opencodemobile.shared.domain.connection.ServerIdentityException
import org.opencodemobile.shared.domain.connection.ServerProfile
import org.opencodemobile.shared.domain.connection.ServerVersion
import org.opencodemobile.shared.security.identity.ServerIdentityAuthorization
import org.opencodemobile.shared.security.identity.ServerIdentityGate
import org.opencodemobile.shared.security.identity.ServerIdentityPinController

/**
 * The sole consumer of the generated OpenAPI client (Rule R3 / ADR-0002).
 *
 * Its connection path is also the T1 enforcement point
 * (`docs/ARCHITECTURE.md` §"Server identity verification (T1)") and the
 * handshake/policy gate (`docs/ARCHITECTURE.md` §3.1, §4.4):
 *
 * 1. [HttpConnectionPolicy] is evaluated first. A plaintext HTTP profile aimed
 *    at a public host is rejected with [ConnectionPolicyException.Rejected]
 *    before any request is built or the credential is released.
 * 2. [ServerIdentityGate] observes the server's presented identity and decides
 *    whether the credential may be released. A first contact or a changed
 *    fingerprint throws [ServerIdentityException] **before** any request is
 *    built.
 * 3. Only on [ServerIdentityAuthorization.Authorized] is the credential permit
 *    set. The generated client's auth provider reads that permit, so the
 *    `Authorization` header cannot be attached by a caller that skipped the
 *    check, and cannot survive [disconnect].
 * 4. The platform TLS engine additionally enforces the pin during the
 *    handshake (defense in depth), so even a future bug that reorders the two
 *    steps cannot send the credential over an unverified connection.
 *    [identityPin] is required and must be the same instance passed to
 *    [createOpenCodeHttpClient]/`OpenCodeHttpClient.create`, otherwise this
 *    backstop silently disappears.
 * 5. `GET /global/health` is read and its version is gated by
 *    [compatibilityProfile]; an unreachable, unhealthy, incomplete, or
 *    incompatible server throws [HandshakeException] and tears the connection
 *    down.
 *
 * The adapter holds a single active connection: [connect] starts by clearing any
 * previous permit, and the permit state is process-global. Concurrent [connect]
 * calls on one instance are not supported.
 *
 * @param compatibilityProfile the version gate applied to the server-reported
 *   version. Defaults to [CompatibilityProfile.OpenCodeServerV2].
 */
public class OpenCodeV2Adapter(
    private val httpClient: HttpClient,
    private val identityGate: ServerIdentityGate,
    private val identityPin: ServerIdentityPinController,
    private val compatibilityProfile: CompatibilityProfile = CompatibilityProfile.OpenCodeServerV2,
) : OpenCodeGateway {

    @Volatile
    private var credentialPermit: ServerCredential? = null

    @Volatile
    private var authorizationsEnabled: Boolean = false

    @Volatile
    private var plaintextWarning: String? = null

    // The credential is released before the health probe, so any failure —
    // including an `Error` — must revoke it before the failure propagates.
    // Catching the generic supertype is deliberate; the throwable is rethrown.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun connect(
        profile: ServerProfile,
        credential: ServerCredential?,
    ): ConnectionHandshake {
        disconnect()

        // 1. Connection policy: fail closed before any request or credential release.
        val scope = when (val policy = HttpConnectionPolicy.decide(profile)) {
            is HttpConnectionPolicyDecision.Rejected ->
                throw ConnectionPolicyException.Rejected(policy)

            is HttpConnectionPolicyDecision.Allowed -> policy.scope
        }

        // 2. Identity is verified: only now may the credential be released.
        val identityCheck = when (val authorization = identityGate.authorize(profile)) {
            is ServerIdentityAuthorization.Authorized -> {
                identityPin.setExpectedPin(authorization.fingerprint)
                plaintextWarning = authorization.plaintextWarning
                if (authorization.plaintextWarning != null) {
                    ServerIdentityCheck.PlaintextHttp
                } else {
                    ServerIdentityCheck.Trusted(authorization.fingerprint!!)
                }
            }

            is ServerIdentityAuthorization.ConfirmationRequired ->
                throw ServerIdentityException.ConfirmationRequired(authorization.presented)

            is ServerIdentityAuthorization.Blocked ->
                throw ServerIdentityException.IdentityChanged(
                    previous = authorization.previous,
                    presented = authorization.presented,
                )
        }

        credentialPermit = credential
        authorizationsEnabled = true

        val client = OpenCodeApiClient(
            baseUrl = profile.baseUrl,
            httpClient = httpClient,
            authTokenProvider = { currentCredential() },
        )

        return try {
            // 3. Health probe: the first request on the connection.
            val health = try {
                client.getHealth()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                throw HandshakeException.HealthUnavailable(failure)
            }

            if (!health.healthy) throw HandshakeException.ServerUnhealthy()

            // 4. Version gate: an absent or unparseable version is incomplete,
            //    never implicitly compatible.
            val version = ServerVersion.parseOrNull(health.version)
                ?: throw HandshakeException.Incomplete(
                    "GET /global/health returned no parseable version ('${health.version}')",
                )

            when (val compatibility = compatibilityProfile.evaluate(version)) {
                is CompatibilityResult.Compatible -> ConnectionHandshake(
                    profileId = profile.id,
                    health = ServerHealth(healthy = health.healthy, version = health.version),
                    identity = identityCheck,
                    version = compatibility.version,
                    scope = scope,
                )

                is CompatibilityResult.Incompatible ->
                    throw HandshakeException.Incompatible(compatibility.serverVersion, compatibility.profile)

                CompatibilityResult.Unknown ->
                    throw HandshakeException.Incomplete(
                        "GET /global/health returned no parseable version ('${health.version}')",
                    )
            }
        } catch (failure: Throwable) {
            disconnect()
            throw failure
        }
    }

    override fun disconnect() {
        authorizationsEnabled = false
        credentialPermit = null
        plaintextWarning = null
        identityPin.reset()
    }

    /**
     * The token the generated client may attach, or null. Returning null when
     * no verified connection is active makes the failure mode "no credential
     * sent" rather than "credential leaked".
     */
    private fun currentCredential(): String? =
        if (authorizationsEnabled) credentialPermit?.bearerToken else null

    /** Non-null when the active connection is plaintext HTTP; must be shown as text (T1). */
    public fun plaintextWarningForActiveConnection(): String? = plaintextWarning

    /** Test/diagnostic hook: whether an active connection currently permits the credential. */
    internal fun isCredentialPermitActive(): Boolean = authorizationsEnabled && credentialPermit != null

    /**
     * Test/diagnostic hook (OPE-170): whether this adapter's client was built by
     * the sanctioned [OpenCodeHttpClient.create] factory, that is, JSON
     * [ContentNegotiation] is installed.
     *
     * A composition root that replaces the sanctioned factory with a raw Ktor
     * client (the OPE-151 defect-2 regression) makes this return false, so the
     * `:androidApp` composition-root resolver test fails instead of silently
     * shipping a client that cannot encode or decode JSON.
     */
    public fun negotiatesJsonContent(): Boolean =
        httpClient.pluginOrNull(ContentNegotiation) != null
}
