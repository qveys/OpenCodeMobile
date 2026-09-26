package org.opencodemobile.shared.security.identity

import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.opencodemobile.shared.domain.connection.ServerFingerprint
import org.opencodemobile.shared.domain.connection.ServerIdentityException
import org.opencodemobile.shared.domain.connection.ServerIdentityVerifier
import org.opencodemobile.shared.domain.connection.ServerProfile

/**
 * Android [ServerIdentityVerifier]: opens a TLS probe to the profile's
 * `GET /global/health` and reports the leaf certificate's SPKI fingerprint
 * through a capturing [SpkiPinningTrustManager].
 *
 * The probe is a plain HEAD request with no `Authorization` header, so it can
 * never leak the credential: identity is established before the credential is
 * ever attached.
 */
public class AndroidServerIdentityVerifier(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val timeoutSeconds: Long = 10L,
) : ServerIdentityVerifier {

    override suspend fun presentedFingerprint(profile: ServerProfile): ServerFingerprint =
        withContext(ioDispatcher) {
            if (profile.isPlaintextHttp) {
                throw IllegalStateException(
                    "A plaintext HTTP profile has no certificate to verify",
                )
            }

            var captured: ServerFingerprint? = null
            val trustManager = SpkiPinningTrustManager(expectedProvider = { null }) { captured = it }
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
            }
            val client = OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustManager)
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url(profile.baseUrl + HEALTH_PATH)
                .head()
                .build()

            try {
                client.newCall(request).execute().close()
            } finally {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }

            captured ?: throw ServerIdentityException.NoCertificatePresented()
        }

    private companion object {
        const val HEALTH_PATH = "/global/health"
    }
}