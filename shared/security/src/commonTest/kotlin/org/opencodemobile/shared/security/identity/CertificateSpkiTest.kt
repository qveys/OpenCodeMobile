package org.opencodemobile.shared.security.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import org.opencodemobile.shared.domain.connection.ServerFingerprint

class CertificateSpkiTest {

    @Test
    fun extractsSpkiWhenVersionElementIsPresent() {
        val certificate = certificate(includeVersion = true)

        assertEquals(spki.toList(), CertificateSpki.extractSpkiDer(certificate).toList())
    }

    @Test
    fun extractsSpkiWhenVersionElementIsAbsent() {
        val certificate = certificate(includeVersion = false)

        assertEquals(spki.toList(), CertificateSpki.extractSpkiDer(certificate).toList())
    }

    @Test
    fun fingerprintIsSha256OfTheSpki() {
        val certificate = certificate(includeVersion = true)

        assertEquals(
            ServerFingerprint.of(sha256Digest(spki)),
            CertificateSpki.fingerprint(certificate),
        )
    }

    private val spki: ByteArray = der(0x30, byteArrayOf(0x03, 0x04, 0x05))

    /** Minimal but structurally valid certificate fixture; only SPKI is inspected. */
    private fun certificate(includeVersion: Boolean): ByteArray {
        val serial = der(0x02, byteArrayOf(0x01))
        val algorithm = der(0x30, byteArrayOf())
        val name = der(0x30, byteArrayOf())
        val validity = der(0x30, byteArrayOf())
        val version = der(0xA0, der(0x02, byteArrayOf(0x02)))

        val tbsContent = if (includeVersion) {
            concat(version, serial, algorithm, name, validity, name, spki)
        } else {
            concat(serial, algorithm, name, validity, name, spki)
        }
        val tbsCertificate = der(0x30, tbsContent)
        val signature = der(0x03, byteArrayOf(0x00))
        return der(0x30, concat(tbsCertificate, algorithm, signature))
    }

    private fun der(tag: Int, content: ByteArray): ByteArray =
        byteArrayOf(tag.toByte()) + derLength(content.size) + content

    private fun derLength(length: Int): ByteArray =
        if (length < 0x80) {
            byteArrayOf(length.toByte())
        } else {
            byteArrayOf(0x81.toByte(), length.toByte())
        }

    private fun concat(vararg parts: ByteArray): ByteArray {
        var result = ByteArray(0)
        for (part in parts) result += part
        return result
    }
}