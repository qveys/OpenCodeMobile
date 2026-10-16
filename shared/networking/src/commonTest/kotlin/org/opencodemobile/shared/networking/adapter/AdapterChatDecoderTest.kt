package org.opencodemobile.shared.networking.adapter

import kotlin.test.Test
import kotlin.test.assertEquals
import org.opencodemobile.shared.domain.chat.ChatEvent
import org.opencodemobile.shared.domain.chat.TranscriptPart

class AdapterChatDecoderTest {
    @Test
    fun startedBlocksRespectBufferLimit() {
        val decoder = AdapterChatDecoder()
        fun decode(ordinal: Int, phase: String, fields: String = ""): ChatEvent.PartUpdated =
            decoder.decode("session.text.$phase", """{"data":{"assistantMessageID":"msg","sessionID":"ses","ordinal":$ordinal$fields}}""") as ChatEvent.PartUpdated
        decode(0, "started")
        decode(0, "delta", ",\"delta\":\"old\"")
        for (ordinal in 1..256) decode(ordinal, "started")
        assertEquals("new", decode(0, "delta", ",\"delta\":\"new\"").part.text)
    }

    private fun tool(type: String, fields: String): ChatEvent? =
        AdapterChatDecoder().decode(type, """{"type":"$type","data":{"sessionID":"ses","assistantMessageID":"msg","id":"call"$fields}}""")

    @Test
    fun toolCallLifecycleMapsToOneToolPart() {
        val decoder = AdapterChatDecoder()
        fun decode(type: String, fields: String) = decoder.decode(
            type, """{"type":"$type","data":{"sessionID":"ses","assistantMessageID":"msg","id":"call"$fields}}""",
        )
        val part = TranscriptPart("msg:tool:call", "tool", "bash")
        assertEquals(ChatEvent.PartUpdated("msg", "ses", part), decode("session.tool.input.started", ",\"name\":\"bash\""))
        assertEquals(ChatEvent.PartUpdated("msg", "ses", part), decode("session.tool.called", ",\"input\":{}"))
        assertEquals(ChatEvent.PartUpdated("msg", "ses", part), decode("session.tool.success", ",\"content\":[]"))
        decode("session.tool.input.started", ",\"name\":\"bash\"")
        assertEquals(
            ChatEvent.PartUpdated("msg", "ses", part.copy(text = "bash: boom")),
            decoder.decode("session.tool.failed", """{"data":{"sessionID":"ses","assistantMessageID":"msg","id":"call","error":{"message":"boom"}}}"""),
        )
    }

    @Test
    fun toolWithoutStartFallsBackToGenericName() {
        assertEquals(
            ChatEvent.PartUpdated("msg", "ses", TranscriptPart("msg:tool:call", "tool", "tool")),
            tool("session.tool.called", ""),
        )
    }

    @Test
    fun stepFailedMapsToErrorPartAndOtherStepsAreIgnored() {
        val decoder = AdapterChatDecoder()
        assertEquals(
            ChatEvent.PartUpdated("msg", "ses", TranscriptPart("msg:error", "error", "oops")),
            decoder.decode("session.step.failed", """{"data":{"sessionID":"ses","assistantMessageID":"msg","error":"oops"}}"""),
        )
        assertEquals(null, decoder.decode("session.step.ended", """{"data":{"sessionID":"ses","assistantMessageID":"msg"}}"""))
    }
}
