package org.opencodemobile.shared.networking.adapter

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.opencodemobile.shared.domain.chat.ChatEvent
import org.opencodemobile.shared.domain.chat.ChatEventDecoder
import org.opencodemobile.shared.domain.chat.TranscriptPart

/**
 * Decodes the OpenCode v2 `GET /api/event` assistant-content events into
 * [ChatEvent]s. Owns the chat SSE decoding that [OpenCodeV2Adapter] only delegates to.
 *
 * The v2 public stream has no `message.*` events (they are v1-only and never emitted)
 * and never emits `session.message.content.updated`: assistant text arrives as
 * `session.text.*` / `session.reasoning.*` blocks identified by
 * (`assistantMessageID`, `ordinal`). [ChatEvent.PartUpdated] replaces a part with its
 * full text, so deltas are accumulated here per block. The authoritative transcript is
 * `GET /api/session/{id}/message`; a resync replaces whatever this builds (D2).
 *
 * The envelope is `{id, type, data:{...}}`. It is read as a [JsonObject] with a
 * defensive `when`: no `@Serializable` DTO for `data`, unknown types and malformed
 * payloads decode to null.
 *
 * Tool calls (`session.tool.*`, keyed by call `id`) become a `tool` part whose text is the
 * tool name (the domain has no structured tool part; failures append the error message).
 * The part id is `<assistantMessageID>:tool:<callID>`: SSE does not know the content ordinal
 * the REST mapping (`<messageId>:<ordinal>`) uses, so the next REST resync replaces it.
 * `session.step.failed` becomes an `error` part; other `session.step.*` carry no content and
 * decode to null. `session.status` is applied to the realtime state by `EventProcessor`.
 */
internal class AdapterChatDecoder : ChatEventDecoder {

    // ponytail: insertion-ordered, bounded; blocks never closed (dropped stream) are evicted oldest-first.
    private val buffers = LinkedHashMap<String, String>()

    override fun decode(type: String, payload: String): ChatEvent? {
        val root = runCatching { Json.parseToJsonElement(payload) }.getOrNull() as? JsonObject
            ?: return null
        val declaredType = root.stringOrNull("type") ?: type
        val data = root["data"] as? JsonObject ?: return null
        return when (declaredType) {
            "session.text.started" -> block(data, TEXT, TEXT_STARTED)
            "session.text.delta" -> block(data, TEXT, DELTA)
            "session.text.ended" -> block(data, TEXT, ENDED)
            "session.reasoning.started" -> block(data, REASONING, TEXT_STARTED)
            "session.reasoning.delta" -> block(data, REASONING, DELTA)
            "session.reasoning.ended" -> block(data, REASONING, ENDED)
            "session.tool.input.started", "session.tool.called", "session.tool.progress",
            "session.tool.success", "session.tool.failed",
            -> tool(declaredType, data)
            "session.step.failed" -> stepFailed(data)
            else -> null
        }
    }

    private fun tool(type: String, data: JsonObject): ChatEvent? {
        val messageId = data.stringOrNull("assistantMessageID") ?: return null
        val callId = data.stringOrNull("id") ?: return null
        val partId = "$messageId:$TOOL:$callId"
        val name = data.stringOrNull("name") ?: buffers[partId] ?: TOOL
        val closed = type == "session.tool.success" || type == "session.tool.failed"
        if (closed) buffers.remove(partId) else remember(partId, name)
        val text = if (type == "session.tool.failed") {
            data.errorMessage()?.let { "$name: $it" } ?: name
        } else {
            name
        }
        return ChatEvent.PartUpdated(messageId, data.stringOrNull("sessionID"), TranscriptPart(partId, TOOL, text))
    }

    private fun stepFailed(data: JsonObject): ChatEvent? {
        val messageId = data.stringOrNull("assistantMessageID") ?: return null
        val text = data.errorMessage() ?: return null
        return ChatEvent.PartUpdated(
            messageId, data.stringOrNull("sessionID"),
            TranscriptPart(id = "$messageId:$ERROR", type = ERROR, text = text),
        )
    }

    private fun block(data: JsonObject, kind: String, phase: Int): ChatEvent? {
        val messageId = data.stringOrNull("assistantMessageID") ?: return null
        val sessionId = data.stringOrNull("sessionID")
        val ordinal = (data["ordinal"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return null
        val partId = "$messageId:$kind:$ordinal"
        val text = when (phase) {
            TEXT_STARTED -> "".also { remember(partId, it) }
            DELTA -> {
                val delta = data.stringOrNull("delta") ?: return null
                ((buffers[partId] ?: "") + delta).also { remember(partId, it) }
            }
            else -> (data.stringOrNull("text") ?: buffers[partId] ?: "").also { buffers.remove(partId) }
        }
        return ChatEvent.PartUpdated(messageId, sessionId, TranscriptPart(id = partId, type = kind, text = text))
    }

    private fun remember(key: String, value: String) {
        buffers[key] = value
        if (buffers.size > MAX_OPEN_BLOCKS) buffers.remove(buffers.keys.first())
    }

    private companion object {
        const val TEXT = "text"
        const val REASONING = "reasoning"
        const val TOOL = "tool"
        const val ERROR = "error"
        const val TEXT_STARTED = 0
        const val DELTA = 1
        const val ENDED = 2
        const val MAX_OPEN_BLOCKS = 256
    }
}

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** `error` as a plain string or an object carrying `message`. */
private fun JsonObject.errorMessage(): String? = when (val error = this["error"]) {
    is JsonObject -> error.stringOrNull("message")
    is JsonPrimitive -> error.takeIf { it.isString }?.content
    else -> null
}
