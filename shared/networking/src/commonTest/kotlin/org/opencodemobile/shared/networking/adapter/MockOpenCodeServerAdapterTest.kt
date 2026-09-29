package org.opencodemobile.shared.networking.adapter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.opencodemobile.shared.domain.connection.ServerCredential
import org.opencodemobile.shared.domain.connection.ServerFingerprint
import org.opencodemobile.shared.domain.connection.ServerIdentityCheck
import org.opencodemobile.shared.domain.connection.ServerIdentityStore
import org.opencodemobile.shared.domain.connection.ServerIdentityVerifier
import org.opencodemobile.shared.domain.connection.ServerProfile
import org.opencodemobile.shared.security.identity.ServerIdentityGate
import org.opencodemobile.shared.security.identity.ServerIdentityPinController
import org.opencodemobile.shared.security.identity.TofuServerIdentityCoordinator
import org.opencodemobile.shared.testsupport.MockOpenCodeServer
import org.opencodemobile.shared.testsupport.OpenCodeFixtures

private class EmptyIdentityStore : ServerIdentityStore {
    override suspend fun pinnedFingerprint(profileId: String): ServerFingerprint? = null
    override suspend fun storePinnedFingerprint(profileId: String, fingerprint: ServerFingerprint): Unit = Unit
    override suspend fun clearPinnedFingerprint(profileId: String): Unit = Unit
}

private class NeverProbedVerifier : ServerIdentityVerifier {
    override suspend fun presentedFingerprint(profile: ServerProfile): ServerFingerprint =
        error("a plaintext mock profile has no certificate; the verifier must never be reached")
}

/**
 * Real consumer of `shared:test-support` (OPE-114).
 *
 * The point of this test is structural as much as behavioural: it drives the harness from
 * outside its own module, so deleting `MockOpenCodeServer` — or dropping the
 * `shared:test-support` test dependency — fails *this* compilation instead of silently
 * leaving the module with zero consumers. It runs the actual [OpenCodeV2Adapter] against
 * the in-process mock client, so the generated OpenAPI wiring is exercised too.
 */
class MockOpenCodeServerAdapterTest {

    private val profile = ServerProfile(
        id = "mock-profile",
        host = "mock.opencode.test",
        port = 4096,
        tls = ServerProfile.TlsMode.PlaintextHttp,
    )

    @Test
    fun adapterConnectsToTheMockServerAndReadsThePinnedHealth() = runTest {
        val server = MockOpenCodeServer().start()
        try {
            val pin = ServerIdentityPinController()
            val gate = ServerIdentityGate(
                TofuServerIdentityCoordinator(EmptyIdentityStore(), NeverProbedVerifier()),
            )
            val adapter = OpenCodeV2Adapter(server.client, gate, pin)

            val handshake = adapter.connect(profile, ServerCredential("s3cr3t"))

            assertEquals(profile.id, handshake.profileId)
            assertEquals(OpenCodeFixtures.SERVER_VERSION, handshake.health.version)
            assertTrue(handshake.health.healthy)
            assertEquals(ServerIdentityCheck.PlaintextHttp, handshake.identity)
            assertTrue(server.requests.contains("GET /global/health"))
            assertTrue(adapter.isCredentialPermitActive())
        } finally {
            server.stop()
        }
    }
}
