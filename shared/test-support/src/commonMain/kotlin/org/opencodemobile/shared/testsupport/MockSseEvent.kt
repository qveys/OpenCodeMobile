package org.opencodemobile.shared.testsupport

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Public v2 SSE record: id and type live inside the JSON, never SSE fields. */
public data class MockSseEvent(public val id: String, public val type: String, public val data: String) {
    public fun encode(): String = "data: $data\n\n"
}

public object MockSseCodec {
    public fun encode(events: Iterable<MockSseEvent>): String = events.joinToString("") { it.encode() }

    /** Comments are heartbeats; malformed JSON is retained for parser-failure scenarios. */
    public fun decode(raw: String): List<MockSseEvent> {
        val events = mutableListOf<MockSseEvent>()
        val lines = mutableListOf<String>()
        fun flush() {
            if (lines.isEmpty()) return
            val data = lines.joinToString("\n")
            val obj = runCatching { Json.parseToJsonElement(data) as? JsonObject }.getOrNull()
            events += MockSseEvent(
                (obj?.get("id") as? JsonPrimitive)?.content.orEmpty(),
                (obj?.get("type") as? JsonPrimitive)?.content ?: "message",
                data,
            )
            lines.clear()
        }
        raw.lineSequence().forEach { line ->
            when {
                line.isEmpty() -> flush()
                line.startsWith("data:") -> lines += line.removePrefix("data:").removePrefix(" ")
            }
        }
        flush()
        return events
    }
}
