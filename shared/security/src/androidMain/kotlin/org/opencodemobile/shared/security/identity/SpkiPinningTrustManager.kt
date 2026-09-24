package org.opencodemobile.shared.security.identity

import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager
import org.opencodemobile.shared.domain.connection.ServerFingerprint

/**
 * Android half of the T1 pinning check: an [X509TrustManager] that computes the
 * SHA-256 SPKI fingerprint of the presented leaf certificate and enforces the
 * profile's pin before the handshake is allowed to complete
 * (`docs/ARCHITECTURE.md` §"Server identity verification (T1)").
 *
 * It is used in two modes:
 * - **capture** ([expectedProvider] returns null): the handshake is allowed and
 *   the observed fingerprint is reported through [onPresented]. Used to display
 *   a first-contact fingerprint.
 * - **pin** ([expectedProvider] returns a value): a diverging fingerprint aborts
 *   the handshake, so no request — and therefore no credential — can travel over
 *   an unverified connection.
 *
 * This deliberately does not chain to a public CA: self-hosted OpenCode servers
 * normally present a self-signed certificate, which is exactly what TOFU is for.
 */
public class SpkiPinningTrustManager(
    private val expectedProvider: () -> ServerFingerprint? = { null },
    private val onPresented: ((ServerFingerprint) -> Unit)? = null,
) : X509TrustManager {

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        // Client certificates are never requested by the app.
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull()
            ?: throw CertificateException("TLS handshake presented no leaf certificate")
        val presented = CertificateSpki.fingerprint(leaf.encoded)
        onPresented?.invoke(presented)

        val pin = expectedProvider()
        if (!SpkiPinCheck.matches(pin, presented)) {
            throw CertificateException(
                "Server identity changed: pinned ${pin.colonSeparated}, " +
                    "presented ${presented.colonSeparated}",
            )
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}