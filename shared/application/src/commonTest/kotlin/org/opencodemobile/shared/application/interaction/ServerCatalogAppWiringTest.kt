package org.opencodemobile.shared.application.interaction

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.opencodemobile.shared.domain.connection.ServerCredential
import org.opencodemobile.shared.domain.connection.ServerFingerprint
import org.opencodemobile.shared.domain.connection.ServerIdentityStore
import org.opencodemobile.shared.domain.connection.ServerIdentityVerifier
import org.opencodemobile.shared.domain.connection.ServerProfile
import org.opencodemobile.shared.networking.adapter.OpenCodeV2Adapter
import org.opencodemobile.shared.security.identity.ServerIdentityGate
import org.opencodemobile.shared.security.identity.ServerIdentityPinController
import org.opencodemobile.shared.security.identity.TofuServerIdentityCoordinator
import org.opencodemobile.shared.testsupport.MockOpenCodeScenario
import org.opencodemobile.shared.testsupport.MockOpenCodeServer
import org.opencodemobile.shared.testsupport.OpenCodeFixtures

/**
 * V1-09 application wiring, end to end against [MockOpenCodeServer] (OPE-194).
 *
 * It grows the OPE-159 recette's client-seam proof (`ServerCatalogController`
 * over a fake gateway) into the assembled surface: the **real**
 * `OpenCodeV2Adapter` interaction gateway drives the controller against the mock,
 * so the generated-client wiring, the adapter mapping and the server routes are
 * exercised together.
 *
 * Covered:
 * - the models (`GET /provider`) and agents (`GET /agent`) the server exposes are
 *   read verbatim — never a built-in catalog,
 * - a server that answers both routes with empty payloads shows the
 *   [ServerCatalogState.EMPTY_MESSAGE],
 * - a server that does not expose the routes at all (`404`) shows the
 *   [ServerCatalogState.UNSUPPORTED_MESSAGE],
 * - a transient catalog failure (`503`) is an **error**, not "unsupported".
 */
class ServerCatalogAppWiringTest {

    private val servers = mutableListOf<MockOpenCodeServer>()

    private val profile = ServerProfile(
        id = "mock-profile",
        host = "mock.opencode.test",
        port = 4096,
        tls = ServerProfile.TlsMode.PlaintextHttp,
    )

    @AfterTest
    fun tearDown() {
        servers.forEach { it.stop() }
    }

    private fun startServer(scenario: MockOpenCodeScenario): MockOpenCodeServer =
        MockOpenCodeServer(scenario = scenario, expectSuccess = true).start().also { servers += it }

    private suspend fun connectedAdapter(server: MockOpenCodeServer): OpenCodeV2Adapter {
        val pin = ServerIdentityPinController()
        val gate = ServerIdentityGate(
            TofuServerIdentityCoordinator(CatalogEmptyIdentityStore(), CatalogNeverProbedVerifier()),
        )
        val adapter = OpenCodeV2Adapter(server.client, gate, pin)
        adapter.connect(profile, ServerCredential("s3cr3t"))
        return adapter
    }

    @Test
    fun modelsAndAgentsComeFromTheServer() = runTest {
        val server = startServer(MockOpenCodeScenario.Default)
        val controller = ServerCatalogController(connectedAdapter(server))

        val outcome = controller.refresh()

        assertTrue(outcome.isSuccess)
        val state = controller.state.value
        assertEquals(listOf("anthropic", "openai"), state.providers.map { it.id })
        assertEquals(
            listOf(OpenCodeFixtures.MODEL_ID),
            state.providers.first().models.map { it.id },
        )
        assertEquals(listOf("build", "plan"), state.agents.map { it.name })
        assertTrue(state.hasContent)
        assertNull(state.emptyReason, "a populated catalog needs no empty reason")
        assertNull(state.error)
        assertTrue(
            server.requests.contains("GET ${MockOpenCodeServer.PROVIDER_PATH}"),
            "the catalog must come from GET /provider: ${server.requests}",
        )
        assertTrue(
            server.requests.contains("GET ${MockOpenCodeServer.AGENT_PATH}"),
            "the catalog must come from GET /agent: ${server.requests}",
        )
    }

    @Test
    fun aServerThatExposesNothingShowsTheEmptyMessage() = runTest {
        val server = startServer(MockOpenCodeScenario.NoCatalog)
        val controller = ServerCatalogController(connectedAdapter(server))

        assertTrue(controller.refresh().isSuccess)

        val state = controller.state.value
        assertTrue(!state.hasContent)
        assertEquals(ServerCatalogState.EMPTY_MESSAGE, state.emptyReason)
        assertNull(state.error)
    }

    @Test
    fun aServerWithoutCatalogRoutesShowsUnsupported() = runTest {
        val server = startServer(MockOpenCodeScenario.NoCatalogRoutes)
        val controller = ServerCatalogController(connectedAdapter(server))

        assertTrue(controller.refresh().isSuccess)

        val state = controller.state.value
        assertTrue(!state.hasContent)
        assertEquals(ServerCatalogState.UNSUPPORTED_MESSAGE, state.emptyReason)
        assertNull(state.error)
    }

    @Test
    fun aTransientCatalogFailureIsAnErrorNotUnsupported() = runTest {
        val server = startServer(MockOpenCodeScenario.CatalogUnavailable)
        val controller = ServerCatalogController(connectedAdapter(server))

        val outcome = controller.refresh()

        assertTrue(outcome.isFailure)
        val state = controller.state.value
        assertTrue(state.error != null, "a transient outage must surface as an error")
        assertNull(
            state.emptyReason,
            "a transient outage must never be shown as an unsupported surface",
        )
    }
}

private class CatalogEmptyIdentityStore : ServerIdentityStore {
    override suspend fun pinnedFingerprint(profileId: String): ServerFingerprint? = null

    override suspend fun storePinnedFingerprint(
        profileId: String,
        fingerprint: ServerFingerprint,
    ): Unit = Unit

    override suspend fun clearPinnedFingerprint(profileId: String): Unit = Unit
}

private class CatalogNeverProbedVerifier : ServerIdentityVerifier {
    override suspend fun presentedFingerprint(profile: ServerProfile): ServerFingerprint =
        error("a plaintext mock profile has no certificate; the verifier must never be reached")
}
