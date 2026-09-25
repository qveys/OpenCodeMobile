package org.opencodemobile.shared.networking.adapter

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.darwin.Darwin
import org.opencodemobile.shared.security.identity.IosSpkiPinningChallengeDelegate
import org.opencodemobile.shared.security.identity.ServerIdentityPinController

/**
 * iOS actual: a Darwin engine whose `NSURLSession` challenge delegate enforces
 * the pin from [identityPin] during the handshake (T1).
 */
public actual fun createOpenCodeHttpClient(
    identityPin: ServerIdentityPinController,
    configure: HttpClientConfig<*>.() -> Unit,
): HttpClient {
    val delegate = IosSpkiPinningChallengeDelegate(
        expectedProvider = { identityPin.expectedPin() },
        onPresented = { identityPin.reportPresented(it) },
    )

    return HttpClient(Darwin) {
        engine {
            handleChallenge { session, _task, challenge, handler ->
                delegate.URLSession(session, challenge, handler)
            }
        }
        configure()
    }
}