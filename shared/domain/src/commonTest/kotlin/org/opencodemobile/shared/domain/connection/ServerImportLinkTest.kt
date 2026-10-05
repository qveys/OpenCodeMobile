package org.opencodemobile.shared.domain.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ServerImportLinkTest {

    private val fingerprintHex = "ab".repeat(32)

    private fun valid(raw: String): ImportedServerProfile =
        assertIs<ServerImportResult.Valid>(ServerImportLink.parse(raw)).profile

    private fun problem(raw: String): ServerInputProblem =
        assertIs<ServerImportResult.Malformed>(ServerImportLink.parse(raw)).error.problem

    @Test
    fun fullLinkDecodesEveryField() {
        val profile = valid(
            "opencodemobile://import?host=192.168.1.10&port=8080&label=Home%20server" +
                "&tls=https&fp=$fingerprintHex&v=1",
        )

        assertEquals("192.168.1.10", profile.host)
        assertEquals(8080, profile.port)
        assertEquals("Home server", profile.label)
        assertEquals(ServerProfile.TlsMode.Https, profile.tls)
        assertEquals(ServerFingerprint.fromHex(fingerprintHex), profile.fingerprint)
    }

    @Test
    fun defaultsApplyWhenOptionalFieldsAreMissing() {
        val profile = valid("opencodemobile://import?host=host.local")

        assertEquals(4096, profile.port)
        assertEquals(ServerProfile.TlsMode.Https, profile.tls)
        assertNull(profile.label)
        assertNull(profile.fingerprint)
    }

    @Test
    fun httpTransportIsDecoded() {
        assertEquals(
            ServerProfile.TlsMode.PlaintextHttp,
            valid("opencodemobile://import?host=host.local&tls=http").tls,
        )
    }

    @Test
    fun trailingSlashAfterImportIsAccepted() {
        assertEquals("host.local", valid("opencodemobile://import/?host=host.local").host)
    }

    @Test
    fun fingerprintWithColonsIsAccepted() {
        val colonHex = fingerprintHex.chunked(2).joinToString(":")

        assertEquals(
            ServerFingerprint.fromHex(fingerprintHex),
            valid("opencodemobile://import?host=host.local&fp=$colonHex").fingerprint,
        )
    }

    @Test
    fun emptyLinkIsBlank() {
        assertEquals(ServerInputProblem.BLANK, problem(""))
    }

    @Test
    fun foreignSchemeIsRejected() {
        assertEquals(ServerInputProblem.UNKNOWN_SCHEME, problem("https://example.com/import?host=x"))
    }

    @Test
    fun wrongAuthorityIsMalformed() {
        assertEquals(ServerInputProblem.MALFORMED, problem("opencodemobile://other?host=x"))
    }

    @Test
    fun missingQueryMeansMissingHost() {
        assertEquals(ServerInputProblem.MISSING_HOST, problem("opencodemobile://import"))
        assertEquals(ServerInputProblem.MISSING_HOST, problem("opencodemobile://import?port=4096"))
    }

    @Test
    fun aCredentialParameterMakesTheLinkInvalid() {
        assertEquals(
            ServerInputProblem.CREDENTIALS_NOT_ALLOWED,
            problem("opencodemobile://import?host=host.local&token=abc"),
        )
        assertEquals(
            ServerInputProblem.CREDENTIALS_NOT_ALLOWED,
            problem("opencodemobile://import?host=host.local&password=abc"),
        )
    }

    @Test
    fun aNewerFormatVersionIsRejected() {
        assertEquals(
            ServerInputProblem.UNSUPPORTED_VERSION,
            problem("opencodemobile://import?host=host.local&v=2"),
        )
    }

    @Test
    fun anUnknownParameterIsRejected() {
        assertEquals(
            ServerInputProblem.UNKNOWN_PARAMETER,
            problem("opencodemobile://import?host=host.local&wat=1"),
        )
    }

    @Test
    fun aDuplicateParameterIsMalformed() {
        assertEquals(
            ServerInputProblem.MALFORMED,
            problem("opencodemobile://import?host=a&host=b"),
        )
    }

    @Test
    fun invalidValuesAreRejected() {
        assertEquals(ServerInputProblem.INVALID_PORT, problem("opencodemobile://import?host=x&port=abc"))
        assertEquals(ServerInputProblem.PORT_OUT_OF_RANGE, problem("opencodemobile://import?host=x&port=0"))
        assertEquals(ServerInputProblem.INVALID_TLS, problem("opencodemobile://import?host=x&tls=ftp"))
        assertEquals(ServerInputProblem.FINGERPRINT_INVALID, problem("opencodemobile://import?host=x&fp=zz"))
    }

    @Test
    fun anInvalidPercentEscapeIsMalformed() {
        assertEquals(
            ServerInputProblem.MALFORMED,
            problem("opencodemobile://import?host=x&label=%zz"),
        )
    }

    @Test
    fun profileIdAndParseOrNullBehave() {
        val profile = valid("opencodemobile://import?host=Host.Local&port=4096")
        assertEquals("host.local:4096", profile.profileId())
        assertEquals("host.local:4096", profile.toProfile().id)
        assertNull(ServerImportLink.parseOrNull("nope"))
    }
}
