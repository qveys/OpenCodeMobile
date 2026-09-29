package org.opencodemobile.shared.domain.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpConnectionPolicyTest {

    private val plaintext = ServerProfile.TlsMode.PlaintextHttp

    private fun profile(host: String, tls: ServerProfile.TlsMode = ServerProfile.TlsMode.Https) =
        ServerProfile(id = "p1", host = host, port = 4096, tls = tls)

    @Test
    fun classifiesLoopback() {
        assertEquals(ServerNetworkScope.Loopback, HttpConnectionPolicy.scopeOf("127.0.0.1"))
        assertEquals(ServerNetworkScope.Loopback, HttpConnectionPolicy.scopeOf("127.9.9.9"))
        assertEquals(ServerNetworkScope.Loopback, HttpConnectionPolicy.scopeOf("localhost"))
        assertEquals(ServerNetworkScope.Loopback, HttpConnectionPolicy.scopeOf("dev.localhost"))
        assertEquals(ServerNetworkScope.Loopback, HttpConnectionPolicy.scopeOf("::1"))
        assertEquals(ServerNetworkScope.Loopback, HttpConnectionPolicy.scopeOf("[::1]"))
    }

    @Test
    fun classifiesPrivateLanAddresses() {
        val lanHosts = listOf(
            "10.0.0.5",
            "10.255.255.255",
            "172.16.0.1",
            "172.31.255.254",
            "192.168.1.10",
            "169.254.1.1",
            "fe80::1",
            "fd00::2",
            "my-server.local",
        )
        for (host in lanHosts) {
            assertEquals(ServerNetworkScope.Lan, HttpConnectionPolicy.scopeOf(host), host)
        }
    }

    @Test
    fun classifiesTailscaleAddresses() {
        val tailscaleHosts = listOf(
            "100.64.0.1",
            "100.101.102.103",
            "100.127.255.255",
            "machine.tailnet.ts.net",
            "fd7a:115c:a1e0::1",
        )
        for (host in tailscaleHosts) {
            assertEquals(ServerNetworkScope.Tailscale, HttpConnectionPolicy.scopeOf(host), host)
        }
    }

    @Test
    fun classifiesPublicHosts() {
        val publicHosts = listOf(
            "8.8.8.8",
            "172.32.0.1",
            "100.128.0.1",
            "example.com",
            "opencode.example.org",
            "2001:4860:4860::8888",
        )
        for (host in publicHosts) {
            assertEquals(ServerNetworkScope.Public, HttpConnectionPolicy.scopeOf(host), host)
        }
    }

    @Test
    fun httpsIsAllowedForEveryScope() {
        assertEquals(
            HttpConnectionPolicyDecision.Allowed(ServerNetworkScope.Public, plaintext = false),
            HttpConnectionPolicy.decide(profile("example.com")),
        )
        assertEquals(
            HttpConnectionPolicyDecision.Allowed(ServerNetworkScope.Lan, plaintext = false),
            HttpConnectionPolicy.decide(profile("192.168.1.10")),
        )
    }

    @Test
    fun plaintextHttpIsAllowedOnLanAndTailscale() {
        assertEquals(
            HttpConnectionPolicyDecision.Allowed(ServerNetworkScope.Lan, plaintext = true),
            HttpConnectionPolicy.decide(profile("192.168.1.10", plaintext)),
        )
        assertEquals(
            HttpConnectionPolicyDecision.Allowed(ServerNetworkScope.Tailscale, plaintext = true),
            HttpConnectionPolicy.decide(profile("100.64.0.1", plaintext)),
        )
        assertEquals(
            HttpConnectionPolicyDecision.Allowed(ServerNetworkScope.Loopback, plaintext = true),
            HttpConnectionPolicy.decide(profile("127.0.0.1", plaintext)),
        )
    }

    @Test
    fun plaintextHttpToAPublicHostIsRejected() {
        assertEquals(
            HttpConnectionPolicyDecision.Rejected(
                scope = ServerNetworkScope.Public,
                violation = HttpPolicyViolation.PublicPlaintextHttp,
            ),
            HttpConnectionPolicy.decide(profile("example.com", plaintext)),
        )
    }

    @Test
    fun theAllowedMethodSetIsExactlyTheGeneratedClientVerbs() {
        assertEquals(setOf("GET", "POST", "DELETE"), HttpConnectionPolicy.allowedMethods)
    }

    @Test
    fun methodChecksAreCaseInsensitiveAndTrimmed() {
        for (allowed in listOf("GET", "GET ".trim(), " get", "post", "Delete")) {
            assertTrue(HttpConnectionPolicy.isMethodAllowed(allowed), allowed)
        }
        for (rejected in listOf("PUT", "PATCH", "HEAD", "OPTIONS", "TRACE", "CONNECT", "")) {
            assertFalse(HttpConnectionPolicy.isMethodAllowed(rejected), rejected)
        }
    }
}
