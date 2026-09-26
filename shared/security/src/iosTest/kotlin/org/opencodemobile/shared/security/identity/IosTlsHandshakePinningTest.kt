package org.opencodemobile.shared.security.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.opencodemobile.shared.domain.connection.ServerFingerprint
import org.opencodemobile.shared.testsupport.tls.T1TlsTestCertificates
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.setHTTPMethod
import kotlin.coroutines.resume

/**
 * T1 real-handshake validation on the iOS simulator (OPE-94).
 *
 * Drives a real TLS handshake through [IosSpkiPinningChallengeDelegate] with
 * `NSURLSession` against a self-signed server started on the host and reached
 * over `https://127.0.0.1` (the simulator shares the host network stack).
 *
 * An in-process TLS *server* cannot be built with public Kotlin/Native APIs
 * (constructing a `SecIdentity` and a `NWListener` TLS endpoint is not exposed
 * for iOS), so the CI workflow starts `tests/t1/tls_recording_server.py` on
 * ports [T1TlsTestCertificates.SIMULATOR_PORT_A] (genuine cert) and
 * [T1TlsTestCertificates.SIMULATOR_PORT_B] (swapped cert) before this suite
 * runs. The host server records every request, and CI asserts the swapped
 * server's log is empty — independent proof that the fail-closed path sent no
 * request carrying the `Authorization` header.
 *
 * The three acceptance cases:
 * 1. first contact captures the presented fingerprint (TOFU),
 * 2. a certificate swap aborts the handshake (and, per the host log, sends no
 *    request),
 * 3. a matching pin reconnects successfully and does carry the credential.
 */
@OptIn(ExperimentalForeignApi::class)
class IosTlsHandshakePinningTest {

    private val fingerprintA = ServerFingerprint.fromHex(T1TlsTestCertificates.FINGERPRINT_A_HEX)
    private val fingerprintB = ServerFingerprint.fromHex(T1TlsTestCertificates.FINGERPRINT_B_HEX)

    private class Outcome(
        val statusCode: Int?,
        val failed: Boolean,
        val message: String?,
        val presented: ServerFingerprint?,
    )

    @Test
    fun firstContactCapturesPresentedFingerprint() = runBlocking {
        val outcome = perform(
            port = T1TlsTestCertificates.SIMULATOR_PORT_A,
            method = "HEAD",
            expectedPin = null,
            authorization = null,
        )

        assertFalse(outcome.failed, "first contact should complete: ${outcome.message}")
        assertEquals(200, outcome.statusCode)
        assertEquals(fingerprintA, outcome.presented)
    }

    @Test
    fun certificateSwapAbortsHandshakeWithoutSendingAuthorization() = runBlocking {
        val outcome = perform(
            port = T1TlsTestCertificates.SIMULATOR_PORT_B,
            method = "GET",
            expectedPin = fingerprintA,
            authorization = T1TlsTestCertificates.TEST_AUTHORIZATION_VALUE,
        )

        assertTrue(outcome.failed, "handshake against the swapped certificate must fail")
        assertNull(outcome.statusCode)
        assertEquals(fingerprintB, outcome.presented)
    }

    @Test
    fun matchingPinReconnectsAndCarriesAuthorization() = runBlocking {
        val outcome = perform(
            port = T1TlsTestCertificates.SIMULATOR_PORT_A,
            method = "GET",
            expectedPin = fingerprintA,
            authorization = T1TlsTestCertificates.TEST_AUTHORIZATION_VALUE,
        )

        assertFalse(outcome.failed, "matching pin must reconnect: ${outcome.message}")
        assertEquals(200, outcome.statusCode)
        assertEquals(fingerprintA, outcome.presented)
    }

    private suspend fun perform(
        port: Int,
        method: String,
        expectedPin: ServerFingerprint?,
        authorization: String?,
    ): Outcome = suspendCancellableCoroutine { continuation ->
        var captured: ServerFingerprint? = null
        val delegate = IosSpkiPinningChallengeDelegate(
            expectedProvider = { expectedPin },
            onPresented = { captured = it },
        )
        val configuration = NSURLSessionConfiguration.ephemeralSessionConfiguration()
        configuration.timeoutIntervalForRequest = 10.0
        if (authorization != null) {
            configuration.HTTPAdditionalHeaders = mapOf<Any?, Any?>("Authorization" to authorization)
        }
        val session = NSURLSession.sessionWithConfiguration(
            configuration = configuration,
            delegate = delegate,
            delegateQueue = null,
        )

        val url = requireNotNull(NSURL.URLWithString("https://127.0.0.1:$port/global/health")) {
            "invalid test URL"
        }
        val request: NSMutableURLRequest = NSMutableURLRequest.requestWithURL(url)
        request.setHTTPMethod(method)
        request.setTimeoutInterval(10.0)

        val task = session.dataTaskWithRequest(request) { _, response, error ->
            session.invalidateAndCancel()
            continuation.resume(
                Outcome(
                    statusCode = (response as? NSHTTPURLResponse)?.statusCode?.toInt(),
                    failed = error != null,
                    message = error?.localizedDescription,
                    presented = captured,
                ),
            )
        }
        continuation.invokeOnCancellation { task.cancel() }
        task.resume()
    }
}