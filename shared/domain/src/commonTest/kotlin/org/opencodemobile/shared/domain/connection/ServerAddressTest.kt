package org.opencodemobile.shared.domain.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ServerAddressTest {

    private fun valid(raw: String): ServerAddress =
        assertIs<ServerAddressResult.Valid>(ServerAddressParser.parse(raw)).address

    private fun problem(raw: String): ServerInputProblem =
        assertIs<ServerAddressResult.Invalid>(ServerAddressParser.parse(raw)).error.problem

    @Test
    fun bareHostUsesDefaultPortAndHttps() {
        val address = valid("192.168.1.10")

        assertEquals("192.168.1.10", address.host)
        assertEquals(4096, address.port)
        assertEquals(ServerProfile.TlsMode.Https, address.tls)
    }

    @Test
    fun explicitPortIsHonoured() {
        assertEquals(8080, valid("192.168.1.10:8080").port)
    }

    @Test
    fun httpSchemeSelectsPlaintext() {
        assertEquals(ServerProfile.TlsMode.PlaintextHttp, valid("http://192.168.1.10:4096").tls)
        assertEquals(ServerProfile.TlsMode.Https, valid("https://192.168.1.10").tls)
    }

    @Test
    fun tailnetNameIsAccepted() {
        val address = valid("opencode.tailnet.ts.net")

        assertEquals("opencode.tailnet.ts.net", address.host)
        assertEquals(4096, address.port)
    }

    @Test
    fun trailingSlashIsAllowed() {
        assertEquals("host.local", valid("host.local/").host)
    }

    @Test
    fun surroundingWhitespaceIsTrimmed() {
        assertEquals("host.local", valid("  host.local  ").host)
    }

    @Test
    fun bracketedIpv6IsAcceptedAndRebracketedForDisplay() {
        val address = valid("[fd7a:115c:a1e0::1]:4096")

        assertEquals("fd7a:115c:a1e0::1", address.host)
        assertEquals(4096, address.port)
        assertEquals("[fd7a:115c:a1e0::1]:4096", address.authority)
    }

    @Test
    fun profileIdAndProfileAreDeterministic() {
        val address = valid("Host.Local:4096")

        assertEquals("host.local:4096", address.profileId())
        val profile = address.toProfile()
        assertEquals("host.local:4096", profile.id)
        assertEquals("Host.Local", profile.host)
        assertEquals(4096, profile.port)
    }

    @Test
    fun blankInputIsBlankProblem() {
        assertEquals(ServerInputProblem.BLANK, problem(""))
        assertEquals(ServerInputProblem.BLANK, problem("   "))
    }

    @Test
    fun unsupportedSchemeIsRejected() {
        assertEquals(ServerInputProblem.UNKNOWN_SCHEME, problem("ftp://host"))
    }

    @Test
    fun credentialsInTheAddressAreRejected() {
        assertEquals(ServerInputProblem.CREDENTIALS_NOT_ALLOWED, problem("http://user:pass@host"))
    }

    @Test
    fun pathQueryAndFragmentAreRejected() {
        assertEquals(ServerInputProblem.PATH_NOT_ALLOWED, problem("host:8080/path"))
        assertEquals(ServerInputProblem.MALFORMED, problem("host?x=1"))
        assertEquals(ServerInputProblem.MALFORMED, problem("host#frag"))
    }

    @Test
    fun invalidPortsAreRejected() {
        assertEquals(ServerInputProblem.INVALID_PORT, problem("host:abc"))
        assertEquals(ServerInputProblem.INVALID_PORT, problem("host:"))
        assertEquals(ServerInputProblem.PORT_OUT_OF_RANGE, problem("host:0"))
        assertEquals(ServerInputProblem.PORT_OUT_OF_RANGE, problem("host:70000"))
    }

    @Test
    fun invalidHostIsRejected() {
        assertEquals(ServerInputProblem.INVALID_HOST, problem("ho st"))
    }

    @Test
    fun unbracketedIpv6IsRejectedAsAmbiguous() {
        assertEquals(ServerInputProblem.MALFORMED, problem("fd7a:115c:a1e0::1"))
    }

    @Test
    fun parseOrNullMirrorsParse() {
        assertEquals("host.local", ServerAddressParser.parseOrNull("host.local")?.host)
        assertNull(ServerAddressParser.parseOrNull("!!!"))
    }
}
