package org.opencodemobile.shared.security.identity

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import org.opencodemobile.shared.domain.connection.ServerFingerprint
import org.opencodemobile.shared.domain.connection.ServerIdentityException
import org.opencodemobile.shared.domain.connection.ServerIdentityVerifier
import org.opencodemobile.shared.domain.connection.ServerProfile
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURLAuthenticationChallenge
import platform.Foundation.NSURLCredential
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionAuthChallengeDisposition
import platform.Foundation.NSURLSessionAuthChallengeCancelAuthenticationChallenge
import platform.Foundation.NSURLSessionAuthChallengeUseCredential
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDelegateProtocol
import platform.Foundation.NSURL
import platform.Foundation.NSURLSessionTask
import platform.Foundation.credentialForTrust
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.serverTrust
import platform.Foundation.setHTTPMethod
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * iOS half of the T1 pinning check: a `URLSession` challenge delegate that
 * computes the SPKI SHA-256 of the trust chain's leaf certificate and enforces
 * the pinned value before allowing the connection to proceed
 * (`docs/ARCHITECTURE.md` §"Server identity verification (T1)").
 *
 * As on Android it serves both capture mode ([expectedProvider] returns null,
 * used to display a first-contact fingerprint) and pin mode (mismatch cancels
 * the challenge, so no request can be sent over an unverified connection).
 */
@OptIn(ExperimentalForeignApi::class)
public class IosSpkiPinningChallengeDelegate(
    private val expectedProvider: () -> ServerFingerprint? = { null },
    private val onPresented: ((ServerFingerprint) -> Unit)? = null,
) : NSObject(), NSURLSessionDelegateProtocol {

    override fun URLSession(
        session: NSURLSession,
        didReceiveChallenge: NSURLAuthenticationChallenge,
        completionHandler: (NSURLSessionAuthChallengeDisposition, NSURLCredential?) -> Unit,
    ) {
        val trust = didReceiveChallenge.protectionSpace.serverTrust
        val presented = trust?.let { IosCertificate.fingerprint(it) }
        if (presented == null) {
            completionHandler(NSURLSessionAuthChallengeCancelAuthenticationChallenge, null)
            return
        }

        onPresented?.invoke(presented)

        val pin = expectedProvider()
        if (!SpkiPinCheck.matches(pin, presented)) {
            completionHandler(NSURLSessionAuthChallengeCancelAuthenticationChallenge, null)
        } else {
            completionHandler(
                NSURLSessionAuthChallengeUseCredential,
                trust?.let { NSURLCredential.credentialForTrust(it) },
            )
        }
    }
}

/**
 * iOS [ServerIdentityVerifier]: issues a credential-free HEAD request to the
 * profile's `GET /global/health` and reports the leaf SPKI fingerprint captured
 * during the TLS challenge.
 */
@OptIn(ExperimentalForeignApi::class)
public class IosServerIdentityVerifier : ServerIdentityVerifier {

    override suspend fun presentedFingerprint(profile: ServerProfile): ServerFingerprint =
        suspendCancellableCoroutine { continuation ->
            if (profile.isPlaintextHttp) {
                continuation.resumeWithException(
                    IllegalStateException("A plaintext HTTP profile has no certificate to verify"),
                )
                return@suspendCancellableCoroutine
            }

            var captured: ServerFingerprint? = null
            val delegate = IosSpkiPinningChallengeDelegate(expectedProvider = { null }) { captured = it }
            val configuration = NSURLSessionConfiguration.ephemeralSessionConfiguration()
            val session = NSURLSession.sessionWithConfiguration(
                configuration = configuration,
                delegate = delegate,
                delegateQueue = null,
            )

            val url = NSURL.URLWithString(profile.baseUrl + HEALTH_PATH)
            if (url == null) {
                continuation.resumeWithException(
                    IllegalArgumentException("Invalid server URL for profile ${profile.id}"),
                )
                return@suspendCancellableCoroutine
            }
            val request: NSMutableURLRequest = NSMutableURLRequest.requestWithURL(url)
            request.setHTTPMethod("HEAD")
            request.setTimeoutInterval(10.0)

            val task: NSURLSessionTask = session.dataTaskWithRequest(request) { _, _, error ->
                session.invalidateAndCancel()
                val fingerprint = captured
                when {
                    error != null -> continuation.resumeWithException(
                        ServerIdentityException.NoCertificatePresented(),
                    )
                    fingerprint != null -> continuation.resume(fingerprint)
                    else -> continuation.resumeWithException(
                        ServerIdentityException.NoCertificatePresented(),
                    )
                }
            }
            continuation.invokeOnCancellation { task.cancel() }
            task.resume()
        }

    private companion object {
        const val HEALTH_PATH = "/global/health"
    }
}