package org.opencodemobile.shared.domain.connection

import kotlin.test.Test
import kotlin.test.assertEquals

class ServerProfileTest {

    @Test
    fun ipv6HostsAreBracketedInTheAuthorityAndBaseUrl() {
        val profile = ServerProfile(id = "p1", host = "fd7a:115c:a1e0::1", port = 4096)

        assertEquals("[fd7a:115c:a1e0::1]:4096", profile.authority)
        assertEquals("https://[fd7a:115c:a1e0::1]:4096", profile.baseUrl)
    }

    @Test
    fun plaintextIpv6ProfilesUseABracketedHttpBaseUrl() {
        val profile = ServerProfile(
            id = "p1",
            host = "fd7a:115c:a1e0::1",
            port = 80,
            tls = ServerProfile.TlsMode.PlaintextHttp,
        )

        assertEquals("http://[fd7a:115c:a1e0::1]:80", profile.baseUrl)
    }

    @Test
    fun hostnamesAndIpv4LiteralsAreNotBracketed() {
        val hostname = ServerProfile(id = "h", host = "opencode.local", port = 4096)
        assertEquals("opencode.local:4096", hostname.authority)
        assertEquals("https://opencode.local:4096", hostname.baseUrl)

        val ipv4 = ServerProfile(id = "h", host = "192.168.1.10", port = 4096)
        assertEquals("192.168.1.10:4096", ipv4.authority)
    }
}
