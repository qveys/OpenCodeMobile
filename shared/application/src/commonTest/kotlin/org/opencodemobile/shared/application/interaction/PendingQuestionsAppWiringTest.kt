package org.opencodemobile.shared.application.interaction

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.opencodemobile.shared.domain.cache.ConnectionState
import org.opencodemobile.shared.domain.cache.ConnectivityMutationGate
import org.opencodemobile.shared.domain.connection.ServerCredential
import org.opencodemobile.shared.domain.connection.ServerFingerprint
import org.opencodemobile.shared.domain.connection.ServerIdentityStore
import org.opencodemobile.shared.domain.connection.ServerIdentityVerifier
import org.opencodemobile.shared.domain.connection.ServerProfile
import org.opencodemobile.shared.networking.adapter.OpenCodeV2Adapter
import org.opencodemobile.shared.networking.realtime.NetworkingRealtimeTransport
import org.opencodemobile.shared.realtime.EventProcessor
import org.opencodemobile.shared.security.identity.ServerIdentityGate
import org.opencodemobile.shared.security.identity.ServerIdentityPinController
import org.opencodemobile.shared.security.identity.TofuServerIdentityCoordinator
import org.opencodemobile.shared.testsupport.MockOpenCodeScenario
import org.opencodemobile.shared.testsupport.MockOpenCodeServer
import org.opencodemobile.shared.testsupport.OpenCodeFixtures

/**
 * V1-07 application wiring, end to end against [MockOpenCodeServer] (OPE-192).
 *
 * It grows the OPE-159 recette's client-seam proof (`PendingQuestionsController`
 * over a fake gateway) into the assembled surface: the **real**
 * `OpenCodeV2Adapter` interaction gateway, the **real** realtime pipeline
 * (`EventProcessor` + `NetworkingRealtimeTransport`) and the
 * [PendingQuestionsRealtimeBridge] drive the controller against the mock.
 *
 * Covered:
 * - a question is listed from `GET /question` and exposed with its exact header
 *   and options,
 * - **kill → relaunch**: a fresh process (new controller + pipeline) re-reads the
 *   server and shows the question again, with nothing lost,
 * - reply and reject reach the wire with the exact captured decision,
 * - the state blocks the turn while the question is open.
 *
 * These run on [Dispatchers.Default] so the mock's real inter-event delays are
 * honoured, exactly like `PermissionIngressMockServerTest`.
 */
@Suppress("InjectDispatcher") // Real-time mock tests on purpose; dispatchers are not wired into tests.
class PendingQuestionsAppWiringTest {

    private val servers = mutableListOf<MockOpenCodeServer>()
    private val scopes = mutableListOf<CoroutineScope>()

    private val profile = ServerProfile(
        id = "mock-profile",
        host = "localhost",
        port = 4096,
        tls = ServerProfile.TlsMode.PlaintextHttp,
    )

    @AfterTest
    fun tearDown() {
        scopes.forEach { it.cancel() }
        servers.forEach { it.stop() }
    }

    private fun startServer(scenario: MockOpenCodeScenario): MockOpenCodeServer =
        MockOpenCodeServer(scenario = scenario).start().also { servers += it }

    private fun newScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }

    private suspend fun awaitUntil(timeoutMillis: Long = 6_000L, condition: () -> Boolean) {
        withTimeout(timeoutMillis) {
            while (!condition()) delay(5L)
        }
    }

    private suspend fun connectedAdapter(server: MockOpenCodeServer): OpenCodeV2Adapter {
        val pin = ServerIdentityPinController()
        val gate = ServerIdentityGate(
            TofuServerIdentityCoordinator(QuestionsEmptyIdentityStore(), QuestionsNeverProbedVerifier()),
        )
        val adapter = OpenCodeV2Adapter(server.client, gate, pin)
        adapter.connect(profile, ServerCredential("s3cr3t"))
        return adapter
    }

    private fun onlineGate() = ConnectivityMutationGate(ConnectionState.Online)

    @Test
    fun questionSurvivesAKillAgainstTheRealServer() = runTest {
        val server = startServer(MockOpenCodeScenario.PermissionRequest)
        val requestId = OpenCodeFixtures.QUESTION_REQUEST_ID

        val first = rebuildProcess(server)
        withContext(Dispatchers.Default) {
            first.bridge.start(first.scope)
            first.processor.start(first.scope)
            awaitUntil { first.controller.state.value.hasPending }
        }

        val before = first.controller.state.value
        assertTrue(before.blocksTurn, "an open question must block the turn")
        val question = before.questions.single()
        assertEquals(requestId, question.id)
        assertEquals(OpenCodeFixtures.sessions.first().id, question.sessionId)
        assertEquals("Rebase target", question.questions.single().header)
        assertEquals(listOf("main", "release"), question.questions.single().options.map { it.label })

        // Kill: tear the pipeline and the scope down. Nothing is persisted locally.
        first.processor.stop()
        first.scope.cancel()

        // Relaunch: a brand-new process re-reads `GET /question` and must show the
        // very same pending question.
        val second = rebuildProcess(server)
        withContext(Dispatchers.Default) {
            second.bridge.start(second.scope)
            second.processor.start(second.scope)
            awaitUntil { second.controller.state.value.hasPending }
        }

        assertEquals(
            listOf(requestId),
            second.controller.state.value.questions.map { it.id },
            "a kill loses nothing: the server still holds the pending question",
        )
        assertTrue(second.controller.state.value.blocksTurn)
        assertTrue(
            server.requests.count { it.startsWith("GET ${MockOpenCodeServer.QUESTION_PATH}") } >= 2,
            "both processes must have read GET /question: ${server.requests}",
        )

        second.processor.stop()
    }

    @Test
    fun replyAndRejectReachTheWireWithTheExactDecision() = runTest {
        val server = startServer(MockOpenCodeScenario.PermissionRequest)
        val requestId = OpenCodeFixtures.QUESTION_REQUEST_ID
        val controller = PendingQuestionsController(connectedAdapter(server), onlineGate())

        assertFalse(controller.state.value.blocksTurn, "nothing blocks the turn before the refresh")
        assertTrue(controller.refresh().isSuccess)
        assertTrue(controller.state.value.blocksTurn)

        assertTrue(controller.answer(requestId, listOf(listOf("main"))).isSuccess)
        assertTrue(controller.reject(requestId).isSuccess)

        val decisions = server.questionReplies
        assertEquals(2, decisions.size)
        assertEquals("reply", decisions[0].decision)
        assertEquals(requestId, decisions[0].requestID)
        assertEquals(listOf(listOf("main")), decisions[0].answers)
        assertEquals("reject", decisions[1].decision)
    }

    /** A "process": its own adapter, controller, realtime pipeline and bridge. */
    private class ProcessFixture(
        val scope: CoroutineScope,
        val controller: PendingQuestionsController,
        val processor: EventProcessor,
        val bridge: PendingQuestionsRealtimeBridge,
    )

    private suspend fun rebuildProcess(server: MockOpenCodeServer): ProcessFixture {
        val adapter = connectedAdapter(server)
        val controller = PendingQuestionsController(adapter, onlineGate())
        val processor = EventProcessor(
            NetworkingRealtimeTransport(httpClient = server.client, baseUrl = server.baseUrl),
        )
        val bridge = PendingQuestionsRealtimeBridge(
            source = processor,
            controller = controller,
            directory = OpenCodeFixtures.DIRECTORY,
        )
        return ProcessFixture(newScope(), controller, processor, bridge)
    }
}

private class QuestionsEmptyIdentityStore : ServerIdentityStore {
    override suspend fun pinnedFingerprint(profileId: String): ServerFingerprint? = null

    override suspend fun storePinnedFingerprint(
        profileId: String,
        fingerprint: ServerFingerprint,
    ): Unit = Unit

    override suspend fun clearPinnedFingerprint(profileId: String): Unit = Unit
}

private class QuestionsNeverProbedVerifier : ServerIdentityVerifier {
    override suspend fun presentedFingerprint(profile: ServerProfile): ServerFingerprint =
        error("a plaintext mock profile has no certificate; the verifier must never be reached")
}
