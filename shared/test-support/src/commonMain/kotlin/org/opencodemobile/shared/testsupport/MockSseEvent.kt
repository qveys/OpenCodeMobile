package org.opencodemobile.shared.testsupport

/**
 * One scripted Server-Sent Event served by [MockOpenCodeServer] on `GET /event`.
 *
 * The shape mirrors the OpenCode Server v2 SSE stream: `id:` identifies the event,
 * `event:` carries the type, and `data:` carries the JSON payload (which itself
 * repeats `type`/`properties`, exactly like the real server).
 */
public data class MockSseEvent(
    public val id: String,
    public val type: String,
    public val data: String,
) {
    /** Encodes this event into one SSE record (fields plus the terminating blank line). */
    public fun encode(): String = buildString {
        append("id: ").append(id).append('\n')
        append("event: ").append(type).append('\n')
        data.lineSequence().forEach { line -> append("data: ").append(line).append('\n') }
        append('\n')
    }
}

/**
 * Minimal, deterministic SSE encoder/decoder used by [MockOpenCodeServer] and by tests.
 *
 * The decoder is intentionally tolerant: it ignores comment lines (`:` prefix), joins
 * multi-line `data:` fields with `\n`, and defaults the type to `message` when absent.
 * Malformed JSON inside `data:` is preserved verbatim so callers can assert on it.
 */
public object MockSseCodec {
    public fun encode(events: Iterable<MockSseEvent>): String =
        events.joinToString(separator = "") { it.encode() }

    public fun decode(raw: String): List<MockSseEvent> {
        val events = mutableListOf<MockSseEvent>()
        var id: String? = null
        var type: String? = null
        val dataLines = mutableListOf<String>()

        fun flush() {
            if (id != null || type != null || dataLines.isNotEmpty()) {
                events += MockSseEvent(
                    id = id ?: "",
                    type = type ?: "message",
                    data = dataLines.joinToString("\n"),
                )
            }
            id = null
            type = null
            dataLines.clear()
        }

        raw.split('\n').forEach { line ->
            when {
                line.isEmpty() -> flush()
                line.startsWith(":") -> Unit
                line.startsWith("id:") -> id = line.removePrefix("id:").trim()
                line.startsWith("event:") -> type = line.removePrefix("event:").trim()
                line.startsWith("data:") -> dataLines += line.removePrefix("data:").removePrefix(" ")
            }
        }
        flush()
        return events
    }
}