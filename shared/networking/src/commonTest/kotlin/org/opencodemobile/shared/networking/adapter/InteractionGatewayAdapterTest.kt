package org.opencodemobile.shared.networking.adapter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import io.ktor.client.plugins.ServerResponseException
import kotlinx.coroutines.test.runTest
import org.opencodemobile.shared.domain.connection.ServerCredential
import org.opencodemobile.shared.domain.connection.ServerFingerprint
import org.opencodemobile.shared.domain.connection.ServerIdentityStore
import org.opencodemobile.shared.domain.connection.ServerIdentityVerifier
import org.opencodemobile.shared.domain.connection.ServerProfile
import org.opencodemobile.shared.domain.interaction.InteractionNotConnectedException
import org.opencodemobile.shared.security.identity.ServerIdentityGate
import org.opencodemobile.shared.security.identity.ServerIdentityPinController
import org.opencodemobile.shared.security.identity.TofuServerIdentityCoordinator
import org.opencodemobile.shared.testsupport.MockOpenCodeScenario
import org.opencodemobile.shared.testsupport.MockOpenCodeServer
import org.opencodemobile.shared.testsupport.OpenCodeFixtures

private class AdapterTestIdentityStore : ServerIdentityStore {
    override suspend fun pinnedFingerprint(profileId: String): ServerFingerprint? = null
    override suspend fun storePinnedFingerprint(profileId: String, fingerprint: ServerFingerprint): Unit = Unit
    override suspend fun clearPinnedFingerprint(profileId: String): Unit = Unit
}

private class AdapterTestVerifier : ServerIdentityVerifier {
    override suspend fun presentedFingerprint(profile: ServerProfile): ServerFingerprint =
        error("a plaintext mock profile has no certificate; the verifier must never be reached")
}

/**
 * V1-07/V1-08/V1-09 integration test: the real [OpenCodeV2Adapter] against the
 * in-process [MockOpenCodeServer], so the generated client wiring, the domain
 * mapping and the mock routes are exercised together.
 */
class InteractionGatewayAdapterTest {

    private val profile = ServerProfile(
        id = "mock-profile",
        host = "localhost",
        port = 4096,
        tls = ServerProfile.TlsMode.PlaintextHttp,
    )

    private fun adapterFor(server: MockOpenCodeServer): OpenCodeV2Adapter {
        val pin = ServerIdentityPinController()
        val gate = ServerIdentityGate(
            TofuServerIdentityCoordinator(AdapterTestIdentityStore(), AdapterTestVerifier()),
        )
        return OpenCodeV2Adapter(server.client, gate, pin)
    }

    @Test
    fun pendingQuestionsAreReadFromTheServer() = runTest {
        val server = MockOpenCodeServer(MockOpenCodeScenario.PermissionRequest).start()
        try {
            val adapter = adapterFor(server)
            adapter.connect(profile, ServerCredential("s3cr3t"))

            val questions = adapter.pendingQuestions(OpenCodeFixtures.DIRECTORY)

            assertEquals(1, questions.size)
            val question = questions.single()
            assertEquals(OpenCodeFixtures.QUESTION_REQUEST_ID, question.id)
            assertEquals(OpenCodeFixtures.sessions.first().id, question.sessionId)
            assertEquals("Rebase target", question.questions.single().header)
            assertEquals(listOf("main", "release"), question.questions.single().options.map { it.label })
        } finally {
            server.stop()
        }
    }

    @Test
    fun answeringAndRejectingCaptureTheExactDecision() = runTest {
        val server = MockOpenCodeServer(MockOpenCodeScenario.PermissionRequest).start()
        try {
            val adapter = adapterFor(server)
            adapter.connect(profile, ServerCredential("s3cr3t"))

            adapter.answerQuestion(
                requestId = OpenCodeFixtures.QUESTION_REQUEST_ID,
                answers = listOf(listOf("main")),
            )
            adapter.rejectQuestion(OpenCodeFixtures.QUESTION_REQUEST_ID)

            val decisions = server.questionReplies
            assertEquals(2, decisions.size)
            assertEquals("reply", decisions[0].decision)
            assertEquals(OpenCodeFixtures.QUESTION_REQUEST_ID, decisions[0].requestID)
            assertEquals(listOf(listOf("main")), decisions[0].answers)
            assertEquals("reject", decisions[1].decision)
        } finally {
            server.stop()
        }
    }

    @Test
    fun abortHitsTheSessionAbortRouteAndCarriesTheDirectory() = runTest {
        val server = MockOpenCodeServer().start()
        try {
            val adapter = adapterFor(server)
            adapter.connect(profile, ServerCredential("s3cr3t"))

            adapter.abortTurn("ses_mock_0001", OpenCodeFixtures.DIRECTORY)

            assertTrue(
                server.requests.contains("POST /session/ses_mock_0001/abort"),
                "abort must call POST /session/{id}/abort: ${server.requests}",
            )
            val recorded = server.aborts.single()
            assertEquals("ses_mock_0001", recorded.sessionId)
            assertEquals(
                OpenCodeFixtures.DIRECTORY,
                recorded.directory,
                "abort must pass the active directory as a query parameter (ADR-0002 §3.3)",
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun theCatalogComesFromTheProviderAndAgentRoutes() = runTest {
        val server = MockOpenCodeServer().start()
        try {
            val adapter = adapterFor(server)
            adapter.connect(profile, ServerCredential("s3cr3t"))

            val catalog = adapter.serverCatalog()

            assertEquals(listOf("anthropic", "openai"), catalog.providers.map { it.id })
            assertEquals(
                listOf(OpenCodeFixtures.MODEL_ID),
                catalog.providers.first().models.map { it.id },
            )
            assertEquals("anthropic", catalog.providers.first().models.first().providerId)
            assertEquals(mapOf("anthropic" to OpenCodeFixtures.MODEL_ID), catalog.defaultModelByProvider)
            assertEquals(listOf("build", "plan"), catalog.agents.map { it.name })
            assertTrue(catalog.providersAvailable && catalog.agentsAvailable)
            assertTrue(!catalog.isEmpty)
        } finally {
            server.stop()
        }
    }

    @Test
    fun aServerExposingNothingYieldsAnEmptyCatalog() = runTest {
        val server = MockOpenCodeServer(MockOpenCodeScenario.NoCatalog).start()
        try {
            val adapter = adapterFor(server)
            adapter.connect(profile, ServerCredential("s3cr3t"))

            val catalog = adapter.serverCatalog()

            assertTrue(catalog.providers.isEmpty())
            assertTrue(catalog.agents.isEmpty())
            assertTrue(catalog.isEmpty)
            assertTrue(catalog.providersAvailable, "the route answered; it is just empty")
            assertTrue(catalog.agentsAvailable)
        } finally {
            server.stop()
        }
    }

    @Test
    fun aServerWithoutCatalogRoutesReportsThemUnavailable() = runTest {
        val server = MockOpenCodeServer(MockOpenCodeScenario.NoCatalogRoutes, expectSuccess = true).start()
        try {
            val adapter = adapterFor(server)
            adapter.connect(profile, ServerCredential("s3cr3t"))

            val catalog = adapter.serverCatalog()

            assertTrue(catalog.providers.isEmpty() && catalog.agents.isEmpty())
            assertTrue(catalog.isEmpty)
            assertTrue(!catalog.providersAvailable, "a 404 on GET /provider means the route is absent")
            assertTrue(!catalog.agentsAvailable, "a 404 on GET /agent means the route is absent")
        } finally {
            server.stop()
        }
    }

    @Test
    fun aTransientCatalogFailurePropagatesInsteadOfLookingUnsupported() = runTest {
        val server = MockOpenCodeServer(MockOpenCodeScenario.CatalogUnavailable, expectSuccess = true).start()
        try {
            val adapter = adapterFor(server)
            adapter.connect(profile, ServerCredential("s3cr3t"))

            // A 503 is not a missing route: it must not be swallowed into
            // providersAvailable=false / agentsAvailable=false (OPE-194).
            assertFailsWith<ServerResponseException> { adapter.serverCatalog() }
        } finally {
            server.stop()
        }
    }

    @Test
    fun interactionCallsFailClosedWithoutAConnection() = runTest {
        val server = MockOpenCodeServer().start()
        try {
            val adapter = adapterFor(server)

            val failure = assertFailsWith<InteractionNotConnectedException> {
                adapter.pendingQuestions()
            }
            assertEquals("No active OpenCode Server connection", failure.message)

            // A disconnected adapter must not have sent anything to the server.
            assertTrue(server.requests.none { it.startsWith("GET /question") })
        } finally {
            server.stop()
        }
    }
}
