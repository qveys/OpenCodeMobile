package org.opencodemobile.shared.domain.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ServerAddressParserTest {

    @Test
    fun bareHostDefaultsToHttpsAndDefaultPort() {
        val result = ServerAddressParser.parse("opencode.example.com")
        val profile = assertIs<ServerAddressResult.Valid>(result).profile
        assertEquals("opencode.example.com", profile.host)
        assertEquals(ServerAddressParser.DEFAULT_PORT, profile.port)
        assertEquals(ServerProfile.TlsMode.Https, profile.tls)
    }

    @Test
    fun hostAndPortAreParsed() {
        val profile = assertIs<ServerAddressResult.Valid>(
            ServerAddressParser.parse("192.168.1.10:8080"),
        ).profile
        assertEquals("192.168.1.10", profile.host)
        assertEquals(8080, profile.port)
    }

    @Test
    fun httpsSchemeWithTrailingSlashIsAccepted() {
        val profile = assertIs<ServerAddressResult.Valid>(
            ServerAddressParser.parse("https://opencode.example.com:443/"),
        ).profile
        assertEquals("opencode.example.com", profile.host)
        assertEquals(443, profile.port)
        assertEquals(ServerProfile.TlsMode.Https, profile.tls)
    }

    @Test
    fun httpSchemeProducesPlaintextProfile() {
        val profile = assertIs<ServerAddressResult.Valid>(
            ServerAddressParser.parse("http://192.168.1.10"),
        ).profile
        assertEquals(ServerProfile.TlsMode.PlaintextHttp, profile.tls)
        assertEquals(ServerAddressParser.DEFAULT_PORT, profile.port)
    }

    @Test
    fun bracketedIpv6IsParsed() {
        val profile = assertIs<ServerAddressResult.Valid>(
            ServerAddressParser.parse("[fd7a:115c:a1e0::1]:4096"),
        ).profile
        assertEquals("fd7a:115c:a1e0::1", profile.host)
        assertEquals(4096, profile.port)
    }

    @Test
    fun unbracketedIpv6IsRejectedAsAmbiguous() {
        val result = ServerAddressParser.parse("fd7a:115c:a1e0::1")
        assertEquals(ServerAddressError.InvalidHost, assertIs<ServerAddressResult.Invalid>(result).reason)
    }

    @Test
    fun labelIsTrimmedAndBlankBecomesNull() {
        val withLabel = assertIs<ServerAddressResult.Valid>(
            ServerAddressParser.parse("host", label = "  Home  "),
        ).profile
        assertEquals("Home", withLabel.label)

        val blankLabel = assertIs<ServerAddressResult.Valid>(
            ServerAddressParser.parse("host", label = "   "),
        ).profile
        assertNull(blankLabel.label)
    }

    @Test
    fun blankInputIsRejected() {
        assertEquals(
            ServerAddressError.Blank,
            assertIs<ServerAddressResult.Invalid>(ServerAddressParser.parse("   ")).reason,
        )
    }

    @Test
    fun unsupportedSchemeIsRejected() {
        val result = ServerAddressParser.parse("ftp://host:21")
        assertEquals(ServerAddressError.UnsupportedScheme, assertIs<ServerAddressResult.Invalid>(result).reason)
    }

    @Test
    fun credentialsInAddressAreRejected() {
        val result = ServerAddressParser.parse("https://user:pass@host")
        assertEquals(ServerAddressError.CredentialsInAddress, assertIs<ServerAddressResult.Invalid>(result).reason)
    }

    @Test
    fun pathIsRejected() {
        val result = ServerAddressParser.parse("https://host/global/health")
        assertEquals(ServerAddressError.PathNotAllowed, assertIs<ServerAddressResult.Invalid>(result).reason)
    }

    @Test
    fun queryAndFragmentAreRejected() {
        assertEquals(
            ServerAddressError.QueryNotAllowed,
            assertIs<ServerAddressResult.Invalid>(ServerAddressParser.parse("host?x=1")).reason,
        )
        assertEquals(
            ServerAddressError.FragmentNotAllowed,
            assertIs<ServerAddressResult.Invalid>(ServerAddressParser.parse("host#frag")).reason,
        )
    }

    @Test
    fun invalidPortIsRejected() {
        assertEquals(
            ServerAddressError.InvalidPort,
            assertIs<ServerAddressResult.Invalid>(ServerAddressParser.parse("host:0")).reason,
        )
        assertEquals(
            ServerAddressError.InvalidPort,
            assertIs<ServerAddressResult.Invalid>(ServerAddressParser.parse("host:70000")).reason,
        )
        assertEquals(
            ServerAddressError.InvalidHost,
            assertIs<ServerAddressResult.Invalid>(ServerAddressParser.parse("host:abc")).reason,
        )
    }

    @Test
    fun profileIdIsDeterministicAndCaseInsensitive() {
        val lower = ServerAddressParser.profileId("Example.COM", 4096)
        val upper = ServerAddressParser.profileId("example.com", 4096)
        assertEquals(lower, upper)

        val first = assertIs<ServerAddressResult.Valid>(ServerAddressParser.parse("Example.COM:4096")).profile
        val second = assertIs<ServerAddressResult.Valid>(ServerAddressParser.parse("example.com:4096")).profile
        assertEquals(first.id, second.id)
    }
}
