package org.opencodemobile.shared.domain.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ServerImportLinkParserTest {

    private val fingerprintHex = "ab".repeat(32)

    @Test
    fun customSchemeLinkIsParsed() {
        val parsed = assertNotNull(
            ServerImportLinkParser.parseOrNull(
                "opencodemobile://import?host=192.168.1.10&port=9090&label=Home",
            ),
        )
        assertEquals("192.168.1.10", parsed.profile.host)
        assertEquals(9090, parsed.profile.port)
        assertEquals("Home", parsed.profile.label)
        assertEquals(ServerProfile.TlsMode.Https, parsed.profile.tls)
    }

    @Test
    fun portDefaultsToOpenCodeDefault() {
        val parsed = assertNotNull(
            ServerImportLinkParser.parseOrNull("opencodemobile://connect?host=opencode.local"),
        )
        assertEquals(ServerAddressParser.DEFAULT_PORT, parsed.profile.port)
    }

    @Test
    fun fingerprintAndPairingCodeAreCarried() {
        val parsed = assertNotNull(
            ServerImportLinkParser.parseOrNull(
                "opencodemobile://import?host=host&fp=$fingerprintHex&code=ABCDEF",
            ),
        )
        assertEquals(ServerFingerprint.fromHex(fingerprintHex), parsed.fingerprint)
        assertEquals("ABCDEF", parsed.pairingCode)
    }

    @Test
    fun percentEscapesAreDecoded() {
        val parsed = assertNotNull(
            ServerImportLinkParser.parseOrNull("opencodemobile://import?host=host&label=Home%20Lab"),
        )
        assertEquals("Home Lab", parsed.profile.label)
    }

    @Test
    fun aLinkCarryingACredentialIsDiscarded() {
        assertNull(
            ServerImportLinkParser.parseOrNull(
                "opencodemobile://import?host=host&token=super-secret",
            ),
        )
        assertNull(
            ServerImportLinkParser.parseOrNull(
                "opencodemobile://import?host=host&credential=abc",
            ),
        )
    }

    @Test
    fun unknownSchemeIsDiscarded() {
        assertNull(ServerImportLinkParser.parseOrNull("https://random.example.com/import?host=host"))
        assertNull(ServerImportLinkParser.parseOrNull("foo://import?host=host"))
    }

    @Test
    fun verifiedHttpsHostIsAccepted() {
        assertNotNull(
            ServerImportLinkParser.parseOrNull(
                raw = "https://imports.opencodemobile.app/import?host=host&port=4096",
                verifiedImportHosts = setOf("imports.opencodemobile.app"),
            ),
        )
        assertNull(
            ServerImportLinkParser.parseOrNull(
                raw = "https://imports.opencodemobile.app/import?host=host",
                verifiedImportHosts = emptySet(),
            ),
        )
        assertNull(
            ServerImportLinkParser.parseOrNull(
                raw = "https://imports.opencodemobile.app/other?host=host",
                verifiedImportHosts = setOf("imports.opencodemobile.app"),
            ),
        )
    }

    @Test
    fun plaintextTlsParameterIsHonoured() {
        val parsed = assertNotNull(
            ServerImportLinkParser.parseOrNull("opencodemobile://import?host=192.168.1.10&tls=http"),
        )
        assertEquals(ServerProfile.TlsMode.PlaintextHttp, parsed.profile.tls)
    }

    @Test
    fun missingOrInvalidFieldsAreDiscarded() {
        assertNull(ServerImportLinkParser.parseOrNull("opencodemobile://import?port=4096"))
        assertNull(ServerImportLinkParser.parseOrNull("opencodemobile://import?host=host&port=not-a-port"))
        assertNull(ServerImportLinkParser.parseOrNull("opencodemobile://import?host=host&port=0"))
        assertNull(ServerImportLinkParser.parseOrNull("opencodemobile://import?host=host&fp=not-hex"))
        assertNull(ServerImportLinkParser.parseOrNull("opencodemobile://import?host=%zz"))
        assertNull(ServerImportLinkParser.parseOrNull("opencodemobile://unknown?host=host"))
        assertNull(ServerImportLinkParser.parseOrNull(""))
    }

    @Test
    fun sourceIsPropagated() {
        val parsed = assertNotNull(
            ServerImportLinkParser.parseOrNull(
                "opencodemobile://import?host=host",
                source = ServerImportSource.QrCode,
            ),
        )
        assertEquals(ServerImportSource.QrCode, parsed.source)
    }
}
