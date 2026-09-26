package org.opencodemobile.shared.networking.adapter

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import org.opencodemobile.shared.security.identity.ServerIdentityPinController

/**
 * Creates the Ktor [HttpClient] whose TLS engine enforces the pinned server
 * identity at the handshake (`docs/ARCHITECTURE.md` §"Server identity
 * verification (T1)").
 *
 * - Android: an OkHttp engine using a custom [X509TrustManager] (see
 *   `SpkiPinningTrustManager`).
 * - iOS: a Darwin engine whose `NSURLSession` challenge delegate performs the
 *   SPKI comparison (see `IosSpkiPinningChallengeDelegate`).
 *
 * @param identityPin the channel through which [configure]'s adapter publishes
 *   the expected pin for the profile being connected to and observes the
 *   fingerprint actually presented.
 */
public expect fun createOpenCodeHttpClient(
    identityPin: ServerIdentityPinController,
    configure: HttpClientConfig<*>.() -> Unit = {},
): HttpClient