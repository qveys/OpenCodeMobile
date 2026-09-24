package org.opencodemobile.shared.networking.adapter

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.opencodemobile.shared.domain.connection.ServerCredential
import org.opencodemobile.shared.domain.connection.ServerFingerprint
import org.opencodemobile.shared.domain.connection.ServerIdentityException
import org.opencodemobile.shared.domain.connection.ServerIdentityStore
import org.opencodemobile.shared.domain.connection.ServerIdentityVerifier
import org.opencodemobile.shared.domain.connection.ServerProfile
import org.opencodemobile.shared.security.identity.ServerIdentityGate
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

private class StaticVerifier(private val presented: ServerFingerprint) : ServerIdentityVerifier {
    override suspend fun presentedFingerprint(profile: ServerProfile): ServerFingerprint = presented
}

class OpenCodeV2AdapterTest {

    private val profile = ServerProfile(id = "p1", host = "192.168.1.10", port = 4096)
    private val pinned = fingerprint(1)
    private val credential = ServerCredential("s3cr3t")

    @Test
    fun trustedIdentityConnectsAndReleasesCredential() = runTest {
        val engine = healthEngine()
        val adapter = adapter(engine, store = InMemoryStore(mutableMapOf(profile.id to pinned)), presented = pinned)

        val handshake = adapter.connect(profile, credential)

        assertEquals(profile.id, handshake.profileId)
        assertEquals("1.18.32", handshake.health.version)
        assertEquals(1, engine.requestHistory.size)
        assertEquals(
            "Bearer s3cr3t",
            engine.requestHistory.first().headers[HttpHeaders.Authorization],
        )
    }

    @Test
    fun firstContactFailsClosedBeforeAnyRequest() = runTest {
        val engine = healthEngine()
        val adapter = adapter(engine, store = InMemoryStore(), presented = pinned)

        assertFailsWith<ServerIdentityException.ConfirmationRequired> {
            adapter.connect(profile, credential)
        }
        assertTrue(engine.requestHistory.isEmpty(), "no request may be sent before confirmation")
    }

    @Test
    fun changedIdentityFailsClosedBeforeAnyRequest() = runTest {
        val engine = healthEngine()
        val adapter = adapter(
            engine,
            store = InMemoryStore(mutableMapOf(profile.id to pinned)),
            presented = fingerprint(2),
        )

        assertFailsWith<ServerIdentityException.IdentityChanged> {
            adapter.connect(profile, credential)
        }
        assertTrue(engine.requestHistory.isEmpty(), "no request may be sent on an identity mismatch")
    }

    @Test
    fun plaintextProfileConnectsWithWarningAndNoPin() = runTest {
        val engine = healthEngine()
        val plaintext = profile.copy(tls = ServerProfile.TlsMode.PlaintextHttp)
        val adapter = adapter(engine, store = InMemoryStore(), presented = pinned)

        val handshake = adapter.connect(plaintext, credential)

        assertEquals(
            org.opencodemobile.shared.security.identity.PlaintextHttpWarning.TEXT,
            adapter.plaintextWarningForActiveConnection(),
        )
        assertEquals(
            org.opencodemobile.shared.domain.connection.ServerIdentityCheck.PlaintextHttp,
            handshake.identity,
        )
    }

    @Test
    fun disconnectDropsTheCredentialPermit() = runTest {
        val engine = healthEngine()
        val adapter = adapter(engine, store = InMemoryStore(mutableMapOf(profile.id to pinned)), presented = pinned)

        adapter.connect(profile, credential)
        adapter.disconnect()

        assertEquals(null, adapter.plaintextWarningForActiveConnection())
    }

    private fun adapter(
        engine: MockEngine,
        store: ServerIdentityStore,
        presented: ServerFingerprint,
    ): OpenCodeV2Adapter {
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json() }
        }
        val gate = ServerIdentityGate(
            TofuServerIdentityCoordinator(store, StaticVerifier(presented)),
        )
        return OpenCodeV2Adapter(client, gate)
    }

    private fun healthEngine(): MockEngine = MockEngine { _ ->
        respond(
            content = """{"healthy":true,"version":"1.18.32"}""",
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )
    }

    private fun fingerprint(seed: Int): ServerFingerprint =
        ServerFingerprint.of(ByteArray(32) { (it + seed).toByte() })
}