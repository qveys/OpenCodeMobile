package org.opencodemobile.shared.domain.chat

/**
 * Role of a transcript message, as the server reports it.
 *
 * The wire value is a free-form string; an unknown role is mapped to [Unknown]
 * instead of throwing, so one new server-side role can never break the transcript.
 */
public enum class TranscriptRole {
    User,
    Assistant,
    System,
    Tool,
    Unknown,
    ;

    public companion object {
        /** Maps the server `role` value; null/unknown becomes [Unknown]. */
        public fun fromWire(value: String?): TranscriptRole = when (value?.lowercase()) {
            "user" -> User
            "assistant" -> Assistant
            "system" -> System
            "tool" -> Tool
            else -> Unknown
        }
    }
}

/**
 * One part of a [TranscriptMessage].
 *
 * [id] is the server-issued part id. A streaming server updates the **same** part
 * id as the text grows, so a consumer upserts by [id] instead of appending: that is
 * what makes a reconnect replay idempotent (no duplicate text).
 *
 * [type] is the server part type (`text`, `code`, `tool`, ...). [language] carries
 * the fenced-code language when the server exposes one, else null.
 */
public data class TranscriptPart(
    public val id: String,
    public val type: String,
    public val text: String,
    public val language: String? = null,
)

/** One message of a session transcript, ordered by the server. */
public data class TranscriptMessage(
    public val id: String,
    public val sessionId: String,
    public val role: TranscriptRole,
    public val parts: List<TranscriptPart> = emptyList(),
) {
    /** The concatenated text of the parts, in server order. */
    public val text: String
        get() = parts.joinToString(separator = "") { it.text }
}

/** Agent + model selection for one prompt (values come from the V1-09 catalog). */
public data class ChatModelRef(
    public val providerId: String,
    public val modelId: String,
)

/** A prompt the user asked to send. [text] is trimmed by the caller. */
public data class ChatPrompt(
    public val text: String,
    public val agent: String? = null,
    public val model: ChatModelRef? = null,
)
