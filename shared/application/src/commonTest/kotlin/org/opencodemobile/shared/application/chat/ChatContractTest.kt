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

/**
 * Contract tests for the V1-05 chat surface, pinning the exact payload shapes of
 * the pinned OpenCode Server v2 spec so the mock can never mask a drift again
 * (review findings on OPE-109):
 *
 * - `EventMessagePartUpdated.properties = {sessionID, part, time}` and the
 *   `messageID` lives on the **part**;
 * - `EventMessageUpdated.properties = {sessionID, info}` with **no** `parts`;
 * - `EventMessagePartRemoved.properties = {sessionID, messageID, partID}`.
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
    fun specPartUpdatedCarriesTheMessageIdOnThePart() {
        val payload = """
            {"id":"evt_1","type":"message.part.updated","properties":{
              "sessionID":"$sessionId",
              "part":{"id":"prt_1","sessionID":"$sessionId","messageID":"msg_1",
                      "type":"text","text":"hello","time":{"start":1}},
              "time":2}}
        """.trimIndent()

        val event = decoder().decode("message.part.updated", payload)

        assertTrue(event is ChatEvent.PartUpdated, "expected a PartUpdated, got $event")
        event as ChatEvent.PartUpdated
        assertEquals("msg_1", event.messageId, "the messageID must be read from the part")
        assertEquals("prt_1", event.part.id)
        assertEquals("hello", event.part.text)
        assertEquals(sessionId, event.sessionId)
    }

    @Test
    fun specMessageUpdatedDecodesTheIdentityWithoutParts() {
        val payload = """
            {"id":"evt_2","type":"message.updated","properties":{
              "sessionID":"$sessionId",
              "info":{"id":"msg_1","sessionID":"$sessionId","role":"assistant"}}}
        """.trimIndent()

        val event = decoder().decode("message.updated", payload)

        assertTrue(event is ChatEvent.MessageUpdated, "expected a MessageUpdated, got $event")
        event as ChatEvent.MessageUpdated
        assertEquals("msg_1", event.message.id)
        assertEquals(TranscriptRole.Assistant, event.message.role)
        assertTrue(event.message.parts.isEmpty(), "the spec has no parts on message.updated")
    }

    @Test
    fun specPartRemovedDecodes() {
        val payload = """
            {"id":"evt_3","type":"message.part.removed","properties":{
              "sessionID":"$sessionId","messageID":"msg_1","partID":"prt_1"}}
        """.trimIndent()

        val event = decoder().decode("message.part.removed", payload)

        assertTrue(event is ChatEvent.PartRemoved, "expected a PartRemoved, got $event")
        event as ChatEvent.PartRemoved
        assertEquals("msg_1", event.messageId)
        assertEquals("prt_1", event.partId)
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
