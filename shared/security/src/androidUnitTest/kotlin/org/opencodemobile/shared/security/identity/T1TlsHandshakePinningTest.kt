package org.opencodemobile.shared.security.identity

import java.io.IOException
import java.security.SecureRandom
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.Request
import org.opencodemobile.shared.domain.connection.ServerFingerprint
import org.opencodemobile.shared.testsupport.tls.SelfSignedTlsServer
import org.opencodemobile.shared.testsupport.tls.T1TlsTestCertificates

/**
 * T1 real-handshake validation on the JVM/Android unit-test runtime (OPE-94).
 *
 * Unlike the `commonTest` suite, which exercises the decision logic with a
 * fake verifier, this drives a real TLS handshake through the actual
 * [SpkiPinningTrustManager] against an in-process self-signed server, and
 * asserts the three T1 acceptance cases:
 *
 * 1. first contact captures the presented fingerprint (TOFU),
 * 2. a certificate swap aborts the handshake and no request carrying the
 *    `Authorization` header is ever sent,
 * 3. a matching pin reconnects successfully.
 *
 * This runs on every Gradle build with no emulator; the `androidInstrumentedTest`
 * twin runs the identical scenario on a device/emulator.
 */
class T1TlsHandshakePinningTest {

    private val fingerprintA = ServerFingerprint.fromHex(T1TlsTestCertificates.FINGERPRINT_A_HEX)
    private val fingerprintB = ServerFingerprint.fromHex(T1TlsTestCertificates.FINGERPRINT_B_HEX)

    @Test
    fun firstContactCapturesPresentedFingerprint() {
        SelfSignedTlsServer(T1TlsTestCertificates.CERT_A_PEM, T1TlsTestCertificates.KEY_A_PKCS8_PEM).use { server ->
            var captured: ServerFingerprint? = null
            val client = clientFor(
                SpkiPinningTrustManager(expectedProvider = { null }, onPresented = { captured = it }),
            )

            client.newCall(unauthenticatedRequest(server)).execute().use { response ->
                assertEquals(200, response.code)
            }

            assertEquals(fingerprintA, captured)
            assertTrue(server.authorizationHeaders().isEmpty())
        }
    }

    @Test
    fun certificateSwapAbortsHandshakeWithoutSendingAuthorization() {
        SelfSignedTlsServer(T1TlsTestCertificates.CERT_B_PEM, T1TlsTestCertificates.KEY_B_PKCS8_PEM).use { server ->
            val client = clientFor(SpkiPinningTrustManager(expectedProvider = { fingerprintA }))

            assertFailsWith<IOException> {
                client.newCall(authenticatedRequest(server)).execute().use { it.close() }
            }

            assertTrue(server.requests().isEmpty(), "no HTTP request may reach the swapped server")
            assertTrue(
                server.authorizationHeaders().isEmpty(),
                "the Authorization header must never leave the device on an unverified connection",
            )
        }
    }

    @Test
    fun matchingPinReconnectsAndCarriesAuthorization() {
        SelfSignedTlsServer(T1TlsTestCertificates.CERT_A_PEM, T1TlsTestCertificates.KEY_A_PKCS8_PEM).use { server ->
            val client = clientFor(SpkiPinningTrustManager(expectedProvider = { fingerprintA }))

            client.newCall(authenticatedRequest(server)).execute().use { response ->
                assertEquals(200, response.code)
            }

            assertEquals(
                listOf(T1TlsTestCertificates.TEST_AUTHORIZATION_VALUE),
                server.authorizationHeaders(),
            )
        }
    }

    private fun clientFor(trustManager: SpkiPinningTrustManager): OkHttpClient {
        val context = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        }
        return OkHttpClient.Builder()
            .sslSocketFactory(context.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    private fun unauthenticatedRequest(server: SelfSignedTlsServer): Request =
        Request.Builder()
            .url("${server.baseUrl}/global/health")
            .head()
            .build()

    private fun authenticatedRequest(server: SelfSignedTlsServer): Request =
        Request.Builder()
            .url("${server.baseUrl}/global/health")
            .header("Authorization", T1TlsTestCertificates.TEST_AUTHORIZATION_VALUE)
            .build()
}