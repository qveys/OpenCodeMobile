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
import org.opencode.mobile.networking.client.generated.apis.OpenCodeApiClient
import org.opencode.mobile.networking.client.generated.models.ApiSessionStatus
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
        // Loopback so the plaintext-HTTP profile passes HttpConnectionPolicy
        // (a public plaintext host is rejected by design, OPE-218 S3).
        host = "localhost",
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

    /**
     * OPE-131 point 4: the generated client must decode `GET /session/status` into a non-empty
     * `Map<String, ApiSessionStatus>`. Before this fixture the route answered `{}`, so fallback
     * polling (the reconciliation step in `docs/ARCHITECTURE.md` §3.2) had no state to read.
     */
    @Test
    fun generatedClientDecodesTheSessionStatusFixture() = runTest {
        val server = MockOpenCodeServer().start()
        try {
            val api = OpenCodeApiClient(server.baseUrl, server.client)
            val statuses: Map<String, ApiSessionStatus> = api.getSessionStatus()

            assertEquals(OpenCodeFixtures.sessions.size, statuses.size)
            val active = statuses.getValue(OpenCodeFixtures.sessions.first().id)
            assertEquals("retry", active.type)
            assertEquals(OpenCodeFixtures.SESSION_STATUS_RETRY_ATTEMPT, active.attempt)
            assertEquals(OpenCodeFixtures.SESSION_STATUS_RETRY_MESSAGE, active.message)
            assertEquals(OpenCodeFixtures.SESSION_STATUS_RETRY_NEXT, active.next)
            assertEquals("idle", statuses.getValue(OpenCodeFixtures.sessions[1].id).type)
        } finally {
            server.stop()
        }
    }
}
