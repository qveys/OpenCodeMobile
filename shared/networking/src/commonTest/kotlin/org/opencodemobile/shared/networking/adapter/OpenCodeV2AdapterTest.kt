package org.opencodemobile.shared.networking.adapter

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.opencodemobile.shared.domain.connection.BasicAuthCredential
import org.opencodemobile.shared.domain.connection.CompatibilityProfile
import org.opencodemobile.shared.domain.connection.ConnectionPolicyException
import org.opencodemobile.shared.domain.connection.HandshakeException
import org.opencodemobile.shared.domain.connection.HttpPolicyViolation
import org.opencodemobile.shared.domain.connection.ServerCredential
import org.opencodemobile.shared.domain.connection.ServerFingerprint
import org.opencodemobile.shared.domain.connection.ServerIdentityException
import org.opencodemobile.shared.domain.connection.ServerIdentityStore
import org.opencodemobile.shared.domain.connection.ServerIdentityVerifier
import org.opencodemobile.shared.domain.connection.ServerNetworkScope
import org.opencodemobile.shared.domain.connection.ServerProfile
import org.opencodemobile.shared.domain.connection.ServerVersion
import org.opencodemobile.shared.security.identity.ServerIdentityGate
import org.opencodemobile.shared.security.identity.ServerIdentityPinController
import org.opencodemobile.shared.security.identity.TofuServerIdentityCoordinator

private class InMemoryStore(
    private val entries: MutableMap<String, ServerFingerprint> = mutableMapOf(),
) : ServerIdentityStore {
    override suspend fun pinnedFingerprint(profileId: String): ServerFingerprint? = entries[profileId]
    override suspend fun storePinnedFingerprint(profileId: String, fingerprint: ServerFingerprint) {
        entries[profileId] = fingerprint
    }

    override suspend fun clearPinnedFingerprint(profileId: String) {
        entries.remove(profileId)
    }
}

private class StaticVerifier(
    private val presented: ServerFingerprint,
    private val onVerify: () -> Unit = {},
) : ServerIdentityVerifier {
    override suspend fun presentedFingerprint(profile: ServerProfile): ServerFingerprint {
        onVerify()
        return presented
    }
}

private class Harness(
    val adapter: OpenCodeV2Adapter,
    val pin: ServerIdentityPinController,
    val engine: MockEngine,
    val basicAuth: BasicAuthCredentialController,
    val events: MutableList<String>,
)

class OpenCodeV2AdapterTest {

    private val profile = ServerProfile(id = "p1", host = "192.168.1.10", port = 4096)
    private val pinned = fingerprint(1)
    private val credential = ServerCredential("s3cr3t")
    private val proxyCredential = BasicAuthCredential("proxy-user", "pr0xy-pass")

    @Test
    fun trustedIdentityConnectsReleasesCredentialAndArmsTheHandshakePin() = runTest {
        val harness = harness(
            store = InMemoryStore(mutableMapOf(profile.id to pinned)),
            presented = pinned,
        )

        val handshake = harness.adapter.connect(profile, credential)

        assertEquals(profile.id, handshake.profileId)
        assertEquals(ServerVersion(2, 0, 6), handshake.version)
        assertEquals(ServerNetworkScope.Lan, handshake.scope)
        assertEquals(1, harness.engine.requestHistory.size)
        assertEquals(
            "Bearer s3cr3t",
            harness.engine.requestHistory.first().headers[HttpHeaders.Authorization],
        )
        assertEquals(pinned, harness.pin.expectedPin())
    }

    @Test
    fun handshakeProbesGetApiInfoOnly() = runTest {
        val harness = harness(
            store = InMemoryStore(mutableMapOf(profile.id to pinned)),
            presented = pinned,
        )

        harness.adapter.connect(profile, credential)

        val request = harness.engine.requestHistory.single()
        assertEquals(HttpMethod.Get, request.method)
        assertEquals("/api/info", request.url.encodedPath)
    }

    @Test
    fun noCredentialIsEverCarriedInARequestUrl() = runTest {
        val harness = harness(
            store = InMemoryStore(mutableMapOf(profile.id to pinned)),
            presented = pinned,
        )

        harness.adapter.connect(profile, credential, proxyCredential)

        harness.engine.requestHistory.forEach { request ->
            val url = request.url.toString()
            assertEquals(null, request.url.user)
            assertEquals(null, request.url.password)
            assertFalse(url.contains("@"), "credentials must not be embedded in the URL")
            listOf("s3cr3t", "proxy-user", "pr0xy-pass").forEach {
                assertFalse(url.contains(it), "credential material found in the URL")
            }
        }
    }

    @Test
    fun firstContactFailsClosedBeforeAnyRequest() = runTest {
        val harness = harness(store = InMemoryStore(), presented = pinned)

        assertFailsWith<ServerIdentityException.ConfirmationRequired> {
            harness.adapter.connect(profile, credential, proxyCredential)
        }
        assertTrue(harness.engine.requestHistory.isEmpty(), "no request may be sent before confirmation")
        assertFalse(harness.adapter.isCredentialPermitActive())
        assertEquals(null, harness.basicAuth.currentCredential(), "TOFU refusal must release no Basic credential")
    }

    @Test
    fun changedIdentityFailsClosedBeforeAnyRequest() = runTest {
        val harness = harness(
            store = InMemoryStore(mutableMapOf(profile.id to pinned)),
            presented = fingerprint(2),
        )

        assertFailsWith<ServerIdentityException.IdentityChanged> {
            harness.adapter.connect(profile, credential, proxyCredential)
        }
        assertTrue(harness.engine.requestHistory.isEmpty(), "no request may be sent on an identity mismatch")
        assertFalse(harness.adapter.isCredentialPermitActive())
        assertEquals(null, harness.basicAuth.currentCredential(), "identity mismatch must release no Basic credential")
    }

    @Test
    fun credentialsAreReleasedOnlyAfterTheIdentityCheckAndBeforeTheFirstRequest() = runTest {
        val harness = harness(
            store = InMemoryStore(mutableMapOf(profile.id to pinned)),
            presented = pinned,
        )

        harness.adapter.connect(profile, credential, proxyCredential)

        assertEquals(
            listOf("verify:permit=false,basic=false", "request:permit=true,basic=true"),
            harness.events,
        )
    }

    @Test
    fun plaintextProfileConnectsWithWarningAndNoPin() = runTest {
        val harness = harness(store = InMemoryStore(), presented = pinned)
        val plaintext = profile.copy(tls = ServerProfile.TlsMode.PlaintextHttp)

        val handshake = harness.adapter.connect(plaintext, credential)

        assertEquals(
            org.opencodemobile.shared.security.identity.PlaintextHttpWarning.TEXT,
            harness.adapter.plaintextWarningForActiveConnection(),
        )
        assertEquals(
            org.opencodemobile.shared.domain.connection.ServerIdentityCheck.PlaintextHttp,
            handshake.identity,
        )
        assertEquals(null, harness.pin.expectedPin())
    }

    @Test
    fun tailscalePlaintextProfileConnects() = runTest {
        val harness = harness(store = InMemoryStore(), presented = pinned)
        val tailscale = ServerProfile(
            id = "p-ts",
            host = "100.64.0.1",
            port = 4096,
            tls = ServerProfile.TlsMode.PlaintextHttp,
        )

        val handshake = harness.adapter.connect(tailscale, credential)

        assertEquals(ServerNetworkScope.Tailscale, handshake.scope)
        assertEquals(ServerVersion(2, 0, 6), handshake.version)
    }

    @Test
    fun publicPlaintextProfileIsRejectedBeforeAnyRequest() = runTest {
        val harness = harness(store = InMemoryStore(), presented = pinned)
        val publicHttp = ServerProfile(
            id = "p-public",
            host = "opencode.example.com",
            port = 4096,
            tls = ServerProfile.TlsMode.PlaintextHttp,
        )

        val failure = assertFailsWith<ConnectionPolicyException.Rejected> {
            harness.adapter.connect(publicHttp, credential, proxyCredential)
        }

        assertEquals(HttpPolicyViolation.PublicPlaintextHttp, failure.decision.violation)
        assertEquals(ServerNetworkScope.Public, failure.decision.scope)
        assertTrue(harness.engine.requestHistory.isEmpty(), "no request may be sent for a rejected profile")
        assertFalse(harness.adapter.isCredentialPermitActive())
        assertEquals(null, harness.basicAuth.currentCredential())
    }

    @Test
    fun todayServerVersionIsCompatible() = runTest {
        val handshake = connectWithVersion("2.0.6")

        assertEquals(ServerVersion(2, 0, 6), handshake.version)
    }

    @Test
    fun theMinimumSupportedVersionIsCompatible() = runTest {
        val handshake = connectWithVersion("2.0.0")

        assertEquals(ServerVersion(2, 0, 0), handshake.version)
    }

    @Test
    fun aPrereleaseOfACompatibleVersionIsTolerated() = runTest {
        val handshake = connectWithVersion("2.0.0-beta.1")

        assertEquals(ServerVersion(2, 0, 0), handshake.version)
    }

    @Test
    fun legacyMajorOneServerFailsTheHandshake() = runTest {
        val harness = harness(
            store = InMemoryStore(mutableMapOf(profile.id to pinned)),
            presented = pinned,
            engine = infoEngine(body = infoJson("1.18.32")),
        )

        val failure = assertFailsWith<HandshakeException.Incompatible> {
            harness.adapter.connect(profile, credential, proxyCredential)
        }

        assertEquals(ServerVersion(1, 18, 32), failure.serverVersion)
        assertFalse(harness.adapter.isCredentialPermitActive())
        assertEquals(null, harness.basicAuth.currentCredential())
    }

    @Test
    fun aFutureMajorServerFailsTheHandshake() = runTest {
        val harness = harness(
            store = InMemoryStore(mutableMapOf(profile.id to pinned)),
            presented = pinned,
            engine = infoEngine(body = infoJson("3.0.0")),
        )

        val failure = assertFailsWith<HandshakeException.Incompatible> {
            harness.adapter.connect(profile, credential)
        }

        assertEquals(ServerVersion(3, 0, 0), failure.serverVersion)
        assertFalse(harness.adapter.isCredentialPermitActive())
    }

    @Test
    fun serverOlderThanTheSupportedMinimumFailsTheHandshake() = runTest {
        val harness = harness(
            store = InMemoryStore(mutableMapOf(profile.id to pinned)),
            presented = pinned,
            engine = infoEngine(body = infoJson("2.0.6")),
            compatibilityProfile = CompatibilityProfile(
                majorVersion = 2,
                minimumVersion = ServerVersion(2, 1, 0),
            ),
        )

        assertFailsWith<HandshakeException.Incompatible> {
            harness.adapter.connect(profile, credential)
        }
    }

    @Test
    fun aNonVersionInfoReplyIsAnIncompleteHandshake() = runTest {
        val harness = harness(
            store = InMemoryStore(mutableMapOf(profile.id to pinned)),
            presented = pinned,
            engine = infoEngine(body = infoJson("not-a-version")),
        )

        assertFailsWith<HandshakeException.Incomplete> {
            harness.adapter.connect(profile, credential, proxyCredential)
        }
        assertFalse(harness.adapter.isCredentialPermitActive())
        assertEquals(null, harness.basicAuth.currentCredential())
    }

    @Test
    fun aBlankVersionIsAnIncompleteHandshake() = runTest {
        val harness = harness(
            store = InMemoryStore(mutableMapOf(profile.id to pinned)),
            presented = pinned,
            engine = infoEngine(body = infoJson("")),
        )

        assertFailsWith<HandshakeException.Incomplete> {
            harness.adapter.connect(profile, credential)
        }
        assertFalse(harness.adapter.isCredentialPermitActive())
    }

    @Test
    fun anInfoReplyWithoutVersionIsATypedHandshakeError() = runTest {
        val harness = harness(
            store = InMemoryStore(mutableMapOf(profile.id to pinned)),
            presented = pinned,
            engine = infoEngine(body = """{"pid":6,"urls":[],"paths":{"tmp":"/t"}}"""),
        )

        assertFailsWith<HandshakeException.InfoUnavailable> {
            harness.adapter.connect(profile, credential)
        }
        assertFalse(harness.adapter.isCredentialPermitActive())
    }

    @Test
    fun anErrorInfoReplyResetsBothCredentialsAndThePin() = runTest {
        val harness = harness(
            store = InMemoryStore(mutableMapOf(profile.id to pinned)),
            presented = pinned,
            engine = infoEngine(body = """{"error":"not found"}""", status = HttpStatusCode.NotFound),
        )

        assertFailsWith<HandshakeException.InfoUnavailable> {
            harness.adapter.connect(profile, credential, proxyCredential)
        }
        assertFalse(harness.adapter.isCredentialPermitActive())
        assertEquals(null, harness.basicAuth.currentCredential(), "Basic credential must be reset on info error")
        assertEquals(null, harness.pin.expectedPin())
    }

    @Test
    fun anUnreachableInfoProbeIsATypedHandshakeErrorAndResetsBothCredentials() = runTest {
        val harness = harness(
            store = InMemoryStore(mutableMapOf(profile.id to pinned)),
            presented = pinned,
            engine = MockEngine { throw IllegalStateException("connection refused") },
        )

        assertFailsWith<HandshakeException.InfoUnavailable> {
            harness.adapter.connect(profile, credential, proxyCredential)
        }
        assertFalse(harness.adapter.isCredentialPermitActive())
        assertEquals(null, harness.basicAuth.currentCredential())
        assertEquals(null, harness.pin.expectedPin())
    }

    @Test
    fun aTransientTransportFailureOnTheInfoProbeIsRetriedOnce() = runTest {
        var attempts = 0
        val harness = harness(
            store = InMemoryStore(mutableMapOf(profile.id to pinned)),
            presented = pinned,
            engine = MockEngine { _ ->
                attempts++
                if (attempts == 1) throw IllegalStateException("tunnel still negotiating")
                respond(
                    content = infoJson("2.0.6"),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            },
        )

        val handshake = harness.adapter.connect(profile, credential)

        assertEquals(2, attempts)
        assertEquals(ServerVersion(2, 0, 6), handshake.version)
    }

    @Test
    fun aCustomCompatibilityProfileWidensTheAcceptedRange() = runTest {
        val harness = harness(
            store = InMemoryStore(mutableMapOf(profile.id to pinned)),
            presented = pinned,
            engine = infoEngine(body = infoJson("1.18.32")),
            compatibilityProfile = CompatibilityProfile(
                majorVersion = 1,
                minimumVersion = ServerVersion(1, 18, 0),
            ),
        )

        val handshake = harness.adapter.connect(profile, credential)

        assertEquals(ServerVersion(1, 18, 32), handshake.version)
    }

    @Test
    fun disconnectDropsTheCredentialPermitTheBasicCredentialAndThePin() = runTest {
        val harness = harness(
            store = InMemoryStore(mutableMapOf(profile.id to pinned)),
            presented = pinned,
        )

        harness.adapter.connect(profile, credential, proxyCredential)
        assertTrue(harness.adapter.isCredentialPermitActive())
        assertEquals(proxyCredential, harness.basicAuth.currentCredential())

        harness.adapter.disconnect()

        assertFalse(harness.adapter.isCredentialPermitActive())
        assertEquals(null, harness.basicAuth.currentCredential())
        assertEquals(null, harness.pin.expectedPin())
        assertEquals(null, harness.adapter.plaintextWarningForActiveConnection())
    }

    private suspend fun connectWithVersion(version: String) = harness(
        store = InMemoryStore(mutableMapOf(profile.id to pinned)),
        presented = pinned,
        engine = infoEngine(body = infoJson(version)),
    ).adapter.connect(profile, credential)

    private fun infoJson(version: String): String =
        """{"version":"$version","pid":6,"urls":["http://172.20.0.2:4096"],"paths":{"tmp":"/tmp/opencode"}}"""

    private fun harness(
        store: ServerIdentityStore,
        presented: ServerFingerprint,
        engine: MockEngine? = null,
        compatibilityProfile: CompatibilityProfile = CompatibilityProfile.OpenCodeServerV2,
    ): Harness {
        val events = mutableListOf<String>()
        val basicAuth = BasicAuthCredentialController()
        lateinit var adapter: OpenCodeV2Adapter
        val state = { "permit=${adapter.isCredentialPermitActive()},basic=${basicAuth.currentCredential() != null}" }
        val mock = engine ?: infoEngine(onRequest = { events += "request:${state()}" })
        val client = HttpClient(mock) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val pin = ServerIdentityPinController()
        val gate = ServerIdentityGate(
            TofuServerIdentityCoordinator(store, StaticVerifier(presented) { events += "verify:${state()}" }),
        )
        adapter = OpenCodeV2Adapter(client, gate, pin, compatibilityProfile, basicAuth)
        return Harness(adapter, pin, mock, basicAuth, events)
    }

    private fun infoEngine(
        body: String = infoJson("2.0.6"),
        status: HttpStatusCode = HttpStatusCode.OK,
        onRequest: () -> Unit = {},
    ): MockEngine = MockEngine { _ ->
        onRequest()
        respond(
            content = body,
            status = status,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )
    }

    private fun fingerprint(seed: Int): ServerFingerprint =
        ServerFingerprint.of(ByteArray(32) { (it + seed).toByte() })
}
