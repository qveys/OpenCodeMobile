package org.opencodemobile.shared.application.chat

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.opencodemobile.shared.domain.chat.ChatEvent
import org.opencodemobile.shared.domain.chat.ChatPrompt
import org.opencodemobile.shared.domain.chat.OpenCodeChatGateway
import org.opencodemobile.shared.domain.chat.TranscriptMessage
import org.opencodemobile.shared.domain.chat.TranscriptPart
import org.opencodemobile.shared.domain.chat.TranscriptRole
import org.opencodemobile.shared.domain.connection.ServerFingerprint
import org.opencodemobile.shared.domain.connection.ServerIdentityStore
import org.opencodemobile.shared.domain.connection.ServerIdentityVerifier
import org.opencodemobile.shared.domain.connection.ServerProfile
import org.opencodemobile.shared.networking.adapter.OpenCodeV2Adapter
import org.opencodemobile.shared.security.identity.ServerIdentityGate
import org.opencodemobile.shared.security.identity.ServerIdentityPinController
import org.opencodemobile.shared.security.identity.TofuServerIdentityCoordinator
import org.opencodemobile.shared.testsupport.MockOpenCodeServer

/** Contracts for public OpenCode v2 assistant text and reasoning events.
 * Legacy message identity/removal decoder contracts are replaced because v2
 * exposes neither event; controller-level identity/removal coverage remains below.
 */
class ChatContractTest {

    private val sessionId = "ses_mock_0001"
    private val servers = mutableListOf<MockOpenCodeServer>()

    @AfterTest
    fun tearDown() {
        servers.forEach { it.stop() }
    }

    private fun decoder(): OpenCodeV2Adapter {
        val server = MockOpenCodeServer().start().also { servers += it }
        return OpenCodeV2Adapter(
            httpClient = server.client,
            identityGate = ServerIdentityGate(
                TofuServerIdentityCoordinator(ChatContractIdentityStore(), ChatContractVerifier()),
            ),
            identityPin = ServerIdentityPinController(),
        )
    }

    @Test
    fun specTextEndedCarriesTheAssistantMessageIdAndOrdinal() {
        val payload = """
            {"id":"evt_1","type":"session.text.ended","data":{
              "sessionID":"$sessionId","assistantMessageID":"msg_1",
              "ordinal":0,"text":"hello"}}
        """.trimIndent()

        val event = decoder().decode("session.text.ended", payload)

        assertTrue(event is ChatEvent.PartUpdated, "expected a PartUpdated, got $event")
        assertEquals("msg_1", event.messageId)
        assertEquals("msg_1:text:0", event.part.id)
        assertEquals("text", event.part.type)
        assertEquals("hello", event.part.text)
        assertEquals(sessionId, event.sessionId)
    }

    @Test
    fun specReasoningStartedDecodesTheIdentityWithoutText() {
        val payload = """
            {"id":"evt_2","type":"session.reasoning.started","data":{
              "sessionID":"$sessionId","assistantMessageID":"msg_1","ordinal":1}}
        """.trimIndent()

        val event = decoder().decode("session.reasoning.started", payload)

        assertTrue(event is ChatEvent.PartUpdated, "expected a PartUpdated, got $event")
        assertEquals("msg_1", event.messageId)
        assertEquals(sessionId, event.sessionId)
        assertEquals("msg_1:reasoning:1", event.part.id)
        assertEquals("reasoning", event.part.type)
        assertEquals("", event.part.text)
    }

    @Test
    fun specReasoningDeltasAccumulateAndEndedReplacesOnlyThatBlock() {
        val decoder = decoder()
        fun decode(type: String, content: String): ChatEvent.PartUpdated {
            val event = decoder.decode(type, """
                {"id":"evt_3","type":"$type","data":{
                  "sessionID":"$sessionId","assistantMessageID":"msg_1",
                  "ordinal":1,$content}}
            """.trimIndent())
            assertTrue(event is ChatEvent.PartUpdated, "expected a PartUpdated, got $event")
            assertEquals("msg_1", event.messageId)
            assertEquals(sessionId, event.sessionId)
            assertEquals("msg_1:reasoning:1", event.part.id)
            assertEquals("reasoning", event.part.type)
            return event
        }
        assertEquals("think", decode("session.reasoning.delta", "\"delta\":\"think\"").part.text)
        assertEquals("thinking", decode("session.reasoning.delta", "\"delta\":\"ing\"").part.text)
        assertEquals("done", decode("session.reasoning.ended", "\"text\":\"done\"").part.text)
        assertEquals("fresh", decode("session.reasoning.delta", "\"delta\":\"fresh\"").part.text)
    }

    @Test
    fun messageUpdatedWithoutPartsDoesNotBlankTheStreamedText() = runTest {
        val controller = TranscriptController(
            FakeChatGateway(
                listOf(
                    TranscriptMessage(
                        id = "msg_1",
                        sessionId = sessionId,
                        role = TranscriptRole.Assistant,
                        parts = listOf(TranscriptPart("prt_1", "text", "hello")),
                    ),
                ),
            ),
        )
        controller.open(sessionId)
        assertEquals("hello", controller.state.value.messages.single().text)

        controller.onEvent(
            ChatEvent.MessageUpdated(
                TranscriptMessage(id = "msg_1", sessionId = sessionId, role = TranscriptRole.Assistant),
            ),
        )

        val message = controller.state.value.messages.single()
        assertEquals("hello", message.text, "a message.updated must not erase the streamed parts")
        assertEquals(1, controller.state.value.messages.size)
    }

    @Test
    fun partRemovedDropsOnlyThatPart() = runTest {
        val controller = TranscriptController(
            FakeChatGateway(
                listOf(
                    TranscriptMessage(
                        id = "msg_1",
                        sessionId = sessionId,
                        role = TranscriptRole.Assistant,
                        parts = listOf(
                            TranscriptPart("prt_1", "text", "hello"),
                            TranscriptPart("prt_2", "text", " world"),
                        ),
                    ),
                ),
            ),
        )
        controller.open(sessionId)

        controller.onEvent(ChatEvent.PartRemoved(messageId = "msg_1", partId = "prt_1", sessionId = sessionId))

        val parts = controller.state.value.messages.single().parts
        assertEquals(listOf("prt_2"), parts.map { it.id })
    }
}

private class FakeChatGateway(
    private val transcript: List<TranscriptMessage>,
) : OpenCodeChatGateway {
    override suspend fun transcript(sessionId: String): List<TranscriptMessage> = transcript

    override suspend fun sendPrompt(sessionId: String, prompt: ChatPrompt): Unit = Unit
}

private class ChatContractIdentityStore : ServerIdentityStore {
    override suspend fun pinnedFingerprint(profileId: String): ServerFingerprint? = null
    override suspend fun storePinnedFingerprint(profileId: String, fingerprint: ServerFingerprint): Unit = Unit
    override suspend fun clearPinnedFingerprint(profileId: String): Unit = Unit
}

private class ChatContractVerifier : ServerIdentityVerifier {
    override suspend fun presentedFingerprint(profile: ServerProfile): ServerFingerprint =
        error("no TLS in this contract test")
}
