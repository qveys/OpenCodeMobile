package org.opencodemobile.shared.security.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import org.opencodemobile.shared.domain.connection.ServerFingerprint

/**
 * Locks the TOFU fingerprint of a real, checked-in self-signed certificate to a
 * hard-coded value.
 *
 * Because this runs from `commonTest`, it executes on every target
 * (`androidUnitTest` and `iosTest`), so it asserts that the shared DER walk and
 * each platform's SHA-256 primitive (`MessageDigest` / `CommonCrypto`) produce
 * the *same* SPKI fingerprint for the same certificate. The expected digest was
 * produced independently with OpenSSL
 * (`openssl x509 -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256`).
 */
class ServerFingerprintFixtureTest {

    private val certificateDer: ByteArray = decodeHex(CERTIFICATE_DER_HEX)

    @Test
    fun sharedExtractorMatchesIndependentlyComputedSpkiDigest() {
        val fingerprint = CertificateSpki.fingerprint(certificateDer)

        assertEquals(EXPECTED_COLON_HEX, fingerprint.colonSeparated)
        assertEquals(EXPECTED_HEX, fingerprint.hex)
    }

    @Test
    fun fingerprintRoundTripsThroughHexParsing() {
        assertEquals(
            CertificateSpki.fingerprint(certificateDer),
            ServerFingerprint.fromHex(EXPECTED_COLON_HEX),
        )
    }

    @Test
    fun extractedSpkiBeginsWithASequenceTag() {
        val spki = CertificateSpki.extractSpkiDer(certificateDer)

        assertEquals(0x30, spki[0].toInt() and 0xFF)
    }

    private fun decodeHex(hex: String): ByteArray {
        val compact = hex.filter { !it.isWhitespace() }
        val out = ByteArray(compact.length / 2)
        for (i in out.indices) {
            val high = Character.digit(compact[i * 2], 16)
            val low = Character.digit(compact[i * 2 + 1], 16)
            out[i] = ((high shl 4) or low).toByte()
        }
        return out
    }

    private companion object {
        const val EXPECTED_HEX: String =
            "9a5779f272a3edf8558ff0d235153c2dab6684b70da026974667879dae42365d"

        const val EXPECTED_COLON_HEX: String =
            "9a:57:79:f2:72:a3:ed:f8:55:8f:f0:d2:35:15:3c:2d:" +
                "ab:66:84:b7:0d:a0:26:97:46:67:87:9d:ae:42:36:5d"

        const val CERTIFICATE_DER_HEX: String =
            "30820311308201f9a0030201020214723fa696ef45e74267f463dc89be7218adcc9c92300d06092a864886f70d01010b0500301831163014" +
                "06035504030c0d6f70656e636f64652d74657374301e170d3236303932343231343232325a170d3336303932313231343232325a30183116" +
                "301406035504030c0d6f70656e636f64652d7465737430820122300d06092a864886f70d01010105000382010f003082010a0282010100d6" +
                "1b85c973f61737065dd87b892f42bcb1ba28157423398b1a2ffa37cbbd6ceb314d0c7b4f5771a9d930d7d676f8cda9914e1aefc07fb03d98" +
                "c85ce90210685d2ab7f959522f78d1668b81b61b65e353c1dd3c1d0266e3a97329ea99793ea858aca621ff8ed03d3ede6f0dcac6bcc64d89" +
                "ef4a8802592c5aa456ffc927bf47b2fec6ae571c5ac00b36836d8f76f78fc95e67e642545deb5178b7eebf822fbe83f6c0f66eeadee519f3" +
                "72b915d5bed3d0a36a6e87ba025d9068f8318c47784a842699ab83826734e256df5d4270d2487a2669c5142eacbe1e1c41bcff56e37919f9" +
                "9c464463bc4c6ea9367afbbb3e8b825551068b93dc1f85edf02065804146b10203010001a3533051301d0603551d0e0416041405a4157130" +
                "c4cf84b0e9784e276d0ab16b75a08f301f0603551d2304183016801405a4157130c4cf84b0e9784e276d0ab16b75a08f300f0603551d1301" +
                "01ff040530030101ff300d06092a864886f70d01010b05000382010100a10251225ca8ab80692e2bc585f5cbcb0bf68e43516563da5f6d45" +
                "0fd484e709abf4c505d60509715a732118246e5eb7d24012b338aa36cd1aae677b0e9552a91d9311e3226c0178993449a4f3365965048715" +
                "0637ddbd9247153f5eaf063d46b00a96372f3b82c00b363003f12ee33119266c5f4d9a759db611eb14e74745fcabac37d2408509519e3fbb" +
                "18efdbf4e1758fffdd4deeeda83401a3b4f7f66ee35c8d30cbb524352bd5dda4a42121fe28d20699be4622b1e6616e73eda095700a058fdc" +
                "61fc49e47f459750616df978306da03603b78a8f10584ebd7e2396ab81dc4fe2082c1c8d487d25ce5c2a6ce8178ff73122264cd723a8b2f5" +
                "4c4f864de2"
    }
}