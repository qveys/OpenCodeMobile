package org.opencodemobile.shared.security.identity

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import org.opencodemobile.shared.domain.connection.ServerFingerprint
import org.opencodemobile.shared.domain.connection.ServerIdentityException
import platform.CoreFoundation.CFArrayGetValueAtIndex
import platform.CoreFoundation.CFDataGetBytes
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFRangeMake
import platform.CoreFoundation.CFRelease
import platform.Foundation.NSData
import platform.Foundation.create
import platform.Security.SecCertificateCopyData
import platform.Security.SecCertificateRef
import platform.Security.SecTrustCopyCertificateChain
import platform.Security.SecTrustRef
import platform.posix.memcpy

/**
 * iOS helpers to turn a `SecCertificateRef` from the TLS chain into the SHA-256
 * SPKI fingerprint used for TOFU pinning
 * (`docs/ARCHITECTURE.md` §"Server identity verification (T1)").
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosCertificate {

    /** DER bytes of the certificate, as presented in the handshake. */
    fun derBytes(certificate: SecCertificateRef): ByteArray {
        val data = SecCertificateCopyData(certificate)
        if (data == null) throw ServerIdentityException.NoCertificatePresented()
        try {
            val length = CFDataGetLength(data).toInt()
            if (length <= 0) return ByteArray(0)
            val bytes = ByteArray(length)
            bytes.usePinned { pinned ->
                CFDataGetBytes(
                    data,
                    CFRangeMake(0, length.toLong()),
                    pinned.addressOf(0).reinterpret(),
                )
            }
            return bytes
        } finally {
            CFRelease(data)
        }
    }

    fun fingerprint(certificate: SecCertificateRef): ServerFingerprint =
        CertificateSpki.fingerprint(derBytes(certificate))

    /**
     * Leaf SPKI fingerprint of the trust chain, or `null` when the chain is
     * empty. Uses `SecTrustCopyCertificateChain` — the supported iOS 15+
     * replacement for `SecTrustGetCertificateAtIndex` — and releases the copied
     * array before returning, once the fingerprint has been computed.
     */
    fun fingerprint(trust: SecTrustRef): ServerFingerprint? {
        val chain = SecTrustCopyCertificateChain(trust) ?: return null
        try {
            @Suppress("UNCHECKED_CAST")
            val leaf = CFArrayGetValueAtIndex(chain, 0) as SecCertificateRef?
                ?: return null
            return fingerprint(leaf)
        } finally {
            CFRelease(chain)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray {
    val length = this.length.toInt()
    if (length <= 0) return ByteArray(0)
    val bytes = ByteArray(length)
    bytes.usePinned { pinned ->
        memcpy(pinned.addressOf(0), this.bytes, this.length)
    }
    return bytes
}

@OptIn(ExperimentalForeignApi::class)
internal fun ByteArray.toNSData(): NSData =
    if (isEmpty()) {
        NSData()
    } else {
        usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
        }
    }