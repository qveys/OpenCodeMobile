package org.opencodemobile.shared.networking.adapter

import io.ktor.client.HttpClient
import kotlin.concurrent.Volatile
import org.opencode.mobile.networking.client.generated.apis.OpenCodeApiClient
import org.opencodemobile.shared.domain.connection.ConnectionHandshake
import org.opencodemobile.shared.domain.connection.OpenCodeGateway
import org.opencodemobile.shared.domain.connection.ServerCredential
import org.opencodemobile.shared.domain.connection.ServerHealth
import org.opencodemobile.shared.domain.connection.ServerIdentityCheck
import org.opencodemobile.shared.domain.connection.ServerIdentityException
import org.opencodemobile.shared.domain.connection.ServerProfile
import org.opencodemobile.shared.security.identity.ServerIdentityAuthorization
import org.opencodemobile.shared.security.identity.ServerIdentityGate
import org.opencodemobile.shared.security.identity.ServerIdentityPinController

/**
 * The sole consumer of the generated OpenAPI client (Rule R3 / ADR-0002).
 *
 * Its connection path is also the T1 enforcement point
 * (`docs/ARCHITECTURE.md` §"Server identity verification (T1)"):
 *
 * 1. [ServerIdentityGate] observes the server's presented identity and decides
 *    whether the credential may be released. A first contact or a changed
 *    fingerprint throws [ServerIdentityException] **before** any request is
 *    built.
 * 2. Only on [ServerIdentityAuthorization.Authorized] is the credential permit
 *    set. The generated client's auth provider reads that permit, so the
 *    `Authorization` header cannot be attached by a caller that skipped the
 *    check, and cannot survive [disconnect].
 * 3. The platform TLS engine additionally enforces the pin during the
 *    handshake (defense in depth), so even a future bug that reorders the two
 *    steps cannot send the credential over an unverified connection. [identityPin]
 *    is required and must be the same instance passed to
 *    [createOpenCodeHttpClient]/`OpenCodeHttpClient.create`, otherwise this
 *    backstop silently disappears.
 *
 * The adapter holds a single active connection: [connect] starts by clearing any
 * previous permit, and the permit state is process-global. Concurrent [connect]
 * calls on one instance are not supported.
 */
public class OpenCodeV2Adapter(
    private val httpClient: HttpClient,
    private val identityGate: ServerIdentityGate,
    private val identityPin: ServerIdentityPinController,
) : OpenCodeGateway {

    @Volatile
    private var credentialPermit: ServerCredential? = null

    @Volatile
    private var authorizationsEnabled: Boolean = false

    @Volatile
    private var plaintextWarning: String? = null

    override suspend fun connect(
        profile: ServerProfile,
        credential: ServerCredential?,
    ): ConnectionHandshake {
        disconnect()

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

        // Identity is verified: only now may the credential be released.
        credentialPermit = credential
        authorizationsEnabled = true

        val client = OpenCodeApiClient(
            baseUrl = profile.baseUrl,
            httpClient = httpClient,
            authTokenProvider = { currentCredential() },
        )

        return try {
            val dto = client.getHealth()
            ConnectionHandshake(
                profileId = profile.id,
                health = ServerHealth(healthy = dto.healthy, version = dto.version),
                identity = identityCheck,
            )
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
}