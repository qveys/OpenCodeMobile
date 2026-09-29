package org.opencodemobile.shared.networking.adapter

import org.opencodemobile.shared.domain.connection.CompatibilityProfile
import org.opencodemobile.shared.domain.connection.OpenCodeGateway
import org.opencodemobile.shared.security.identity.ServerIdentityGate
import org.opencodemobile.shared.security.identity.ServerIdentityPinController

/**
 * Builds the process-wide [OpenCodeGateway] over the sanctioned HTTP client.
 *
 * It uses [OpenCodeHttpClient.create] (JSON ContentNegotiation + redaction-safe
 * logging; the platform `createOpenCodeHttpClient` actual applies the outbound
 * transport policy) and returns the domain port, so a composition root never
 * has to name `io.ktor.client.HttpClient`. That keeps the Ktor type inside
 * `shared/networking` instead of leaking it onto the app shell's classpath
 * (which does not depend on Ktor, per §5.2).
 */
public fun createOpenCodeGateway(
    identityPin: ServerIdentityPinController,
    identityGate: ServerIdentityGate,
    compatibilityProfile: CompatibilityProfile = CompatibilityProfile.OpenCodeServerV2,
): OpenCodeGateway = OpenCodeV2Adapter(
    httpClient = OpenCodeHttpClient.create(identityPin),
    identityGate = identityGate,
    identityPin = identityPin,
    compatibilityProfile = compatibilityProfile,
)
