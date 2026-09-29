package org.opencodemobile.shared.application.chat

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.opencodemobile.shared.domain.cache.MutationGate
import org.opencodemobile.shared.domain.chat.ComposerDraftStore
import org.opencodemobile.shared.domain.chat.TranscriptRole
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
import org.opencodemobile.shared.testsupport.MockOpenCodeStreamConfig
import org.opencodemobile.shared.testsupport.OpenCodeFixtures

/**
 * V1-05 chat end to end: the **real** `OpenCodeV2Adapter` chat surface, the
 * **real** realtime pipeline (`EventProcessor` + `NetworkingRealtimeTransport`)
 * and the **real** application controllers drive [MockOpenCodeServer].
 *
 * Covered: the streaming scenario, kill/reconstitution with no duplicate or lost
 * text, disconnect/reconnect with the prompt **not** replayed (D9), the
 * long-transcript scenario, and the §8.1 draft invariant.
 *
 * These run on [Dispatchers.Default] so the mock's real inter-event delays are
 * honoured, exactly like `PermissionIngressMockServerTest`.
 */
class ChatTranscriptMockServerTest {

    private val servers = mutableListOf<MockOpenCodeServer>()
    private val scopes = mutableListOf<CoroutineScope>()

    private val sessionId = OpenCodeFixtures.sessions.first().id

    private val profile = ServerProfile(
        id = "mock-profile",
        host = "mock.opencode.test",
        port = 4096,
        tls = ServerProfile.TlsMode.PlaintextHttp,
    )

    @AfterTest
    fun tearDown() {
        scopes.forEach { it.cancel() }
        servers.forEach { it.stop() }
    }

    private fun startServer(
        scenario: MockOpenCodeScenario,
        streamConfig: MockOpenCodeStreamConfig = MockOpenCodeStreamConfig(),
    ): MockOpenCodeServer =
        MockOpenCodeServer(scenario = scenario, streamConfig = streamConfig).start().also { servers += it }

    private fun newScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }

    private suspend fun connectedAdapter(server: MockOpenCodeServer): OpenCodeV2Adapter {
        val pin = ServerIdentityPinController()
        val gate = ServerIdentityGate(
            TofuServerIdentityCoordinator(ChatEmptyIdentityStore(), ChatNeverProbedVerifier()),
        )
        val adapter = OpenCodeV2Adapter(server.client, gate, pin)
        adapter.connect(profile, ServerCredential("s3cr3t"))
        return adapter
    }

    private fun transportFor(server: MockOpenCodeServer): NetworkingRealtimeTransport =
        NetworkingRealtimeTransport(httpClient = server.client, baseUrl = server.baseUrl)

    private suspend fun awaitUntil(timeoutMillis: Long = 6_000L, condition: () -> Boolean) {
        withTimeout(timeoutMillis) {
            while (!condition()) delay(5L)
        }
    }

    @Test
    fun promptProducesAStreamedTranscript() = runTest {
        val server = startServer(
            MockOpenCodeScenario.Streaming,
            MockOpenCodeStreamConfig(streamingDelayMillis = 20L),
        )
        val scope = newScope()
        val adapter = connectedAdapter(server)
        val transcripts = TranscriptController(adapter)
        val composers = ComposerController(adapter, InMemoryDraftStore(), OnlineMutations)
        val processor = EventProcessor(transportFor(server))

        withContext(Dispatchers.Default) {
            composers.open(sessionId)
            composers.updateDraft("Explain the D9 rule")
            assertTrue(composers.send().isSuccess)

            transcripts.open(sessionId)
            val bridge = ChatRealtimeBridge(processor, adapter, transcripts)
            bridge.start(scope)
            processor.start(scope)

            awaitUntil {
                transcripts.state.value.messages.count { it.role == TranscriptRole.Assistant } >= 2
            }

            val state = transcripts.state.value
            val user = state.messages.firstOrNull { it.role == TranscriptRole.User }
            assertEquals("Explain the D9 rule", user?.text)

            val assistant = state.messages
                .filter { it.role == TranscriptRole.Assistant }
                .joinToString(separator = "") { it.text }
            assertTrue(assistant.contains("streamed chunk 1"), "streamed text missing: $assistant")
            assertTrue(assistant.contains("streamed chunk 2"), "streamed text missing: $assistant")

            val ids = state.messages.map { it.id }
            assertEquals(ids.size, ids.toSet().size, "the transcript must not contain duplicates")

            assertEquals(listOf("Explain the D9 rule"), server.prompts.map { it.text })
        }
        processor.stop()
    }

    @Test
    fun transcriptReadsTheSpecMessageEnvelope() = runTest {
        val server = startServer(MockOpenCodeScenario.Streaming)
        val adapter = connectedAdapter(server)

        val messages = adapter.transcript(sessionId)

        assertEquals(2, messages.size, "expected one assistant message per scripted part: $messages")
        assertEquals("msg_mock_0001", messages.first().id)
        assertEquals(TranscriptRole.Assistant, messages.first().role)
        assertEquals("streamed chunk 1", messages.first().text)
    }

    @Test
    fun killMidTurnReconstitutesFromTheServerWithoutDuplicatesOrLostText() = runTest {
        val server = startServer(
            MockOpenCodeScenario.Streaming,
            MockOpenCodeStreamConfig(streamingDelayMillis = 150L),
        )

        // First run: send a prompt and start the turn, then "kill" the app mid-stream.
        val firstScope = newScope()
        val first = connectedAdapter(server)
        val firstTranscripts = TranscriptController(first)
        val firstComposers = ComposerController(first, InMemoryDraftStore(), OnlineMutations)
        val firstProcessor = EventProcessor(transportFor(server))
        withContext(Dispatchers.Default) {
            firstComposers.open(sessionId)
            firstComposers.updateDraft("first turn")
            firstComposers.send()
            firstTranscripts.open(sessionId)
            val bridge = ChatRealtimeBridge(firstProcessor, first, firstTranscripts)
            bridge.start(firstScope)
            firstProcessor.start(firstScope)
            awaitUntil { firstTranscripts.state.value.messages.any { it.role == TranscriptRole.Assistant } }
        }
        firstProcessor.stop()
        firstScope.cancel()

        // Restart: a fresh pipeline reconciles the transcript from the server (D2).
        val secondScope = newScope()
        val second = connectedAdapter(server)
        val secondTranscripts = TranscriptController(second)
        val secondProcessor = EventProcessor(transportFor(server))
        withContext(Dispatchers.Default) {
            secondTranscripts.open(sessionId)
            val bridge = ChatRealtimeBridge(secondProcessor, second, secondTranscripts)
            bridge.start(secondScope)
            secondProcessor.start(secondScope)

            awaitUntil { secondTranscripts.state.value.messages.size >= 3 }

            val ids = secondTranscripts.state.value.messages.map { it.id }
            assertEquals(
                listOf("msg_user_0001", "msg_mock_0001", "msg_mock_0002"),
                ids,
                "the reconstituted transcript must contain every part exactly once",
            )
            assertEquals(1, server.prompts.size, "the restart must never resend the prompt (D9)")
        }
        secondProcessor.stop()
    }

    @Test
    fun reconnectResumesTheTurnAndDoesNotReplayThePrompt() = runTest {
        val server = startServer(
            MockOpenCodeScenario.Disconnect,
            MockOpenCodeStreamConfig(disconnectAfterEvents = 1, disconnectConnections = 1),
        )
        val scope = newScope()
        val adapter = connectedAdapter(server)
        val transcripts = TranscriptController(adapter)
        val composers = ComposerController(adapter, InMemoryDraftStore(), OnlineMutations)
        val processor = EventProcessor(transportFor(server))

        withContext(Dispatchers.Default) {
            composers.open(sessionId)
            composers.updateDraft("ping")
            assertTrue(composers.send().isSuccess)

            transcripts.open(sessionId)
            val bridge = ChatRealtimeBridge(processor, adapter, transcripts)
            bridge.start(scope)
            processor.start(scope)

            awaitUntil(timeoutMillis = 8_000L) {
                server.eventStreamConnections >= 2 &&
                    server.requests.count { it == "GET ${MockOpenCodeServer.SESSION_PATH}" } >= 2
            }
            awaitUntil { transcripts.state.value.messages.any { it.role == TranscriptRole.Assistant } }

            assertEquals(1, server.prompts.size, "a reconnect must not replay the prompt (D9)")
            assertEquals(1, server.prompts.count { it.sessionId == sessionId })
        }
        processor.stop()
    }

    @Test
    fun longTranscriptIsAppliedInFull() = runTest {
        val partCount = 600
        val server = startServer(
            MockOpenCodeScenario.LongTranscript,
            MockOpenCodeStreamConfig(longTranscriptPartCount = partCount),
        )
        val scope = newScope()
        val adapter = connectedAdapter(server)
        val transcripts = TranscriptController(adapter)
        val processor = EventProcessor(transportFor(server))

        withContext(Dispatchers.Default) {
            transcripts.open(sessionId)
            val bridge = ChatRealtimeBridge(processor, adapter, transcripts)
            bridge.start(scope)
            processor.start(scope)

            awaitUntil(timeoutMillis = 15_000L) {
                transcripts.state.value.messages.count { it.role == TranscriptRole.Assistant } >= partCount
            }

            val assistants = transcripts.state.value.messages.filter { it.role == TranscriptRole.Assistant }
            assertEquals(partCount, assistants.size)
            val ids = assistants.map { it.id }
            assertEquals(ids.size, ids.toSet().size, "a long transcript must not duplicate a message")
        }
        processor.stop()
    }

    @Test
    fun restoringADraftNeverSendsIt() = runTest {
        val server = startServer(MockOpenCodeScenario.Streaming)
        val adapter = connectedAdapter(server)
        val store = InMemoryDraftStore().apply { drafts[sessionId] = "an unsent draft" }
        val composers = ComposerController(adapter, store, OnlineMutations)

        composers.open(sessionId)

        assertEquals("an unsent draft", composers.state.value.draft)
        assertTrue(server.prompts.isEmpty(), "restoring a draft must never send a prompt (§8.1)")
    }

    @Test
    fun sendWritesThePromptOnceAndClearsTheDraft() = runTest {
        val server = startServer(MockOpenCodeScenario.Streaming)
        val adapter = connectedAdapter(server)
        val store = InMemoryDraftStore()
        val composers = ComposerController(adapter, store, OnlineMutations)

        composers.open(sessionId)
        composers.updateDraft("hello world")
        assertTrue(composers.send().isSuccess)

        assertEquals("", composers.state.value.draft)
        assertEquals("", store.loadDraft(sessionId))
        assertEquals(listOf("hello world"), server.prompts.map { it.text })
        assertTrue(server.requests.contains("POST /session/$sessionId/prompt_async"))
    }

    @Test
    fun sendIsRefusedOfflineAndTouchesNoWire() = runTest {
        val server = startServer(MockOpenCodeScenario.Streaming)
        val adapter = connectedAdapter(server)
        val composers = ComposerController(adapter, InMemoryDraftStore(), OfflineMutations)

        composers.open(sessionId)
        composers.updateDraft("blocked offline")

        assertTrue(composers.state.value.offline, "the composer must know it is offline")
        assertTrue(!composers.state.value.canSend, "the send affordance must be disabled offline")
        assertTrue(composers.send().isFailure)
        assertTrue(server.prompts.isEmpty(), "offline is read-only: nothing may reach the wire (D8)")
    }
}

private class ChatEmptyIdentityStore : ServerIdentityStore {
    override suspend fun pinnedFingerprint(profileId: String): ServerFingerprint? = null
    override suspend fun storePinnedFingerprint(profileId: String, fingerprint: ServerFingerprint): Unit = Unit
    override suspend fun clearPinnedFingerprint(profileId: String): Unit = Unit
}

private class ChatNeverProbedVerifier : ServerIdentityVerifier {
    override suspend fun presentedFingerprint(profile: ServerProfile): ServerFingerprint =
        error("a plaintext mock profile has no certificate; the verifier must never be reached")
}

/** Online gate: the mock connection is always live. */
internal object OnlineMutations : MutationGate {
    override fun mutationsAllowed(): Boolean = true
}

/** Offline gate: mutations must be refused locally (D8). */
internal object OfflineMutations : MutationGate {
    override fun mutationsAllowed(): Boolean = false
}

/** In-memory draft store that survives the controller, like the local cache does. */
internal class InMemoryDraftStore : ComposerDraftStore {
    internal val drafts: MutableMap<String, String> = mutableMapOf()

    override suspend fun loadDraft(sessionId: String): String = drafts[sessionId].orEmpty()

    override suspend fun saveDraft(sessionId: String, draft: String) {
        drafts[sessionId] = draft
    }
}
