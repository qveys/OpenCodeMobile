package org.opencodemobile.shared.security.identity

import org.opencodemobile.shared.domain.connection.ServerFingerprint

/**
 * Extracts the DER-encoded SubjectPublicKeyInfo (SPKI) from an X.509
 * certificate and hashes it to the TOFU fingerprint used for server identity
 * verification (`docs/ARCHITECTURE.md` §"Server identity verification (T1)").
 *
 * Keeping the DER walk in `commonMain` means Android and iOS share exactly one
 * definition of "the server identity", and that definition is unit-testable
 * without a TLS handshake. The platforms only supply the raw certificate DER
 * and the SHA-256 primitive.
 *
 * SPKI layout inside a certificate:
 * ```
 * Certificate          ::= SEQUENCE { tbsCertificate, signatureAlgorithm, signatureValue }
 * TBSCertificate       ::= SEQUENCE {
 *                            [0] version OPTIONAL,
 *                            serialNumber, signature, issuer, validity, subject,
 *                            subjectPublicKeyInfo, ... }
 * ```
 */
internal object Der {
    /** A parsed TLV: the byte range of its full encoding and of its content. */
    class Tlv internal constructor(
        val tag: Int,
        val start: Int,
        val contentStart: Int,
        val contentEnd: Int,
    ) {
        val end: Int get() = contentEnd
        val totalLength: Int get() = contentEnd - start
    }

    fun read(data: ByteArray, offset: Int): Tlv {
        require(offset + 2 <= data.size) { "Truncated DER at offset $offset" }
        val tag = data[offset].toInt() and 0xFF
        var pos = offset + 1
        var length = data[pos].toInt() and 0xFF
        pos++
        if (length and 0x80 != 0) {
            val count = length and 0x7F
            require(count in 1..4) { "Unsupported DER length width $count" }
            length = 0
            repeat(count) {
                require(pos < data.size) { "Truncated DER length" }
                length = (length shl 8) or (data[pos].toInt() and 0xFF)
                pos++
            }
        }
        val contentEnd = pos + length
        require(contentEnd <= data.size) { "DER value overflows input at offset $offset" }
        return Tlv(tag = tag, start = offset, contentStart = pos, contentEnd = contentEnd)
    }

    fun children(data: ByteArray, parent: Tlv): List<Tlv> {
        val out = ArrayList<Tlv>()
        var cursor = parent.contentStart
        while (cursor < parent.contentEnd) {
            val child = read(data, cursor)
            out.add(child)
            cursor = child.end
        }
        return out
    }
}

internal object CertificateSpki {
    private const val TAG_SEQUENCE = 0x30
    private const val TAG_CONTEXT_0 = 0xA0

    /** Index of `subjectPublicKeyInfo` in `TBSCertificate` when `version` is present. */
    private const val SPKI_INDEX_WITH_VERSION = 6

    /** Index of `subjectPublicKeyInfo` in `TBSCertificate` when `version` is absent. */
    private const val SPKI_INDEX_WITHOUT_VERSION = 5

    /** Returns the full SPKI TLV (tag, length, value) of [certificateDer]. */
    fun extractSpkiDer(certificateDer: ByteArray): ByteArray {
        val certificate = Der.read(certificateDer, 0)
        require(certificate.tag == TAG_SEQUENCE) { "Certificate does not start with a SEQUENCE" }

        val certificateChildren = Der.children(certificateDer, certificate)
        require(certificateChildren.size >= 3) { "Certificate has too few members" }
        val tbsCertificate = certificateChildren[0]

        val tbsChildren = Der.children(certificateDer, tbsCertificate)
        require(tbsChildren.isNotEmpty()) { "TBSCertificate is empty" }
        val spkiIndex =
            if (tbsChildren[0].tag == TAG_CONTEXT_0) SPKI_INDEX_WITH_VERSION else SPKI_INDEX_WITHOUT_VERSION
        require(spkiIndex < tbsChildren.size) { "TBSCertificate is missing subjectPublicKeyInfo" }

        val spki = tbsChildren[spkiIndex]
        return certificateDer.copyOfRange(spki.start, spki.end)
    }

    /** SHA-256 fingerprint of the certificate's SPKI. */
    fun fingerprint(certificateDer: ByteArray): ServerFingerprint =
        ServerFingerprint.of(sha256Digest(extractSpkiDer(certificateDer)))
}

/** SHA-256 of [data]. Platform primitive: `MessageDigest` / CommonCrypto. */
internal expect fun sha256Digest(data: ByteArray): ByteArray