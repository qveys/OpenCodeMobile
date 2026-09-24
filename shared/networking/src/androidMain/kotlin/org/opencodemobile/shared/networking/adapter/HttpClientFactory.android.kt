package org.opencodemobile.shared.security.identity

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import java.security.SecureRandom
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import okhttp3.OkHttpClient
import org.opencodemobile.shared.security.identity.ServerIdentityPinController
import org.opencodemobile.shared.security.identity.SpkiPinningTrustManager

/**
 * Android actual: an OkHttp engine whose `X509TrustManager` enforces the pin
 * from [identityPin] during the handshake (T1).
 */
public actual fun createOpenCodeHttpClient(
    identityPin: ServerIdentityPinController,
    configure: HttpClientConfig<*>.() -> Unit,
): HttpClient {
    val trustManager = SpkiPinningTrustManager(
        expectedProvider = { identityPin.expectedPin() },
        onPresented = { identityPin.reportPresented(it) },
    )
    val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
    }
    val okHttpClient = OkHttpClient.Builder()
        .sslSocketFactory(sslContext.socketFactory, trustManager)
        .build()

    return HttpClient(OkHttp) {
        engine {
            preconfigured = okHttpClient
        }
        configure()
    }
}