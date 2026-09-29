package org.opencodemobile.shared.domain.chat

/**
 * The port for the V1-05 chat surface: reading a session transcript and sending a
 * prompt. Implemented by `OpenCodeV2Adapter` in `shared/networking` (the only
 * module allowed to touch the generated client, Rule R3); the UI has no HTTP path.
 *
 * It is deliberately separate from
 * [org.opencodemobile.shared.domain.connection.OpenCodeGateway]: connection is a
 * lifecycle concern, while these are per-connection operations.
 *
 * Every method throws on failure. Callers (application controllers) turn the
 * failure into user-visible state; nothing is queued or retried here.
 */
public interface OpenCodeChatGateway {
    /**
     * Reads the authoritative transcript of [sessionId]
     * (`GET /session/{sessionID}/message`).
     *
     * This is the reconciliation surface (D2): after a reconnect or an app kill
     * the client replaces its local view with this list, so replayed live events
     * cannot duplicate text.
     */
    public suspend fun transcript(sessionId: String): List<TranscriptMessage>

    /**
     * Sends [prompt] to [sessionId] (`POST /session/{sessionID}/prompt_async`).
     *
     * The call is exactly-once: there is no queue, no retry, and no replay across a
     * reconnect (D9). Re-issuing it would be a second user action.
     */
    public suspend fun sendPrompt(sessionId: String, prompt: ChatPrompt)
}

/**
 * A decoded realtime event that affects the transcript.
 *
 * The event pipeline delivers raw payloads; `shared/networking` decodes them into
 * these domain values so the application layer never parses JSON.
 */
public sealed interface ChatEvent {
    /** A part of [messageId] was created or updated (`message.part.updated`). */
    public data class PartUpdated(
        public val messageId: String,
        public val sessionId: String?,
        public val part: TranscriptPart,
    ) : ChatEvent

    /** A whole message was created or updated (`message.updated`). */
    public data class MessageUpdated(
        public val message: TranscriptMessage,
    ) : ChatEvent

    /** A message was removed (`message.removed`). */
    public data class MessageRemoved(
        public val messageId: String,
        public val sessionId: String?,
    ) : ChatEvent
}

/**
 * Decodes a raw realtime event into a [ChatEvent], or null when the event is
 * unrelated. Implemented in `shared/networking`.
 */
public interface ChatEventDecoder {
    /** Never throws: an undecodable payload is reported as null and dropped. */
    public fun decode(type: String, payload: String): ChatEvent?
}

/**
 * Local persistence of the composer draft (§8.1: the draft is kept locally and is
 * **never sent automatically**).
 *
 * A draft is written as the user types and cleared after an explicit send.
 * Nothing in this port can send a prompt, and restoring a draft never triggers a
 * send — that invariant is enforced in the application composer controller.
 */
public interface ComposerDraftStore {
    /** The last persisted draft for [sessionId], or the empty string. */
    public suspend fun loadDraft(sessionId: String): String

    /** Persists [draft] for [sessionId]; an empty draft clears it. */
    public suspend fun saveDraft(sessionId: String, draft: String)
}

/**
 * Thrown when a chat call is made while no verified connection is active. The
 * generated client's credential permit is released only after the T1 identity
 * check, so there is nothing legitimate to send without a connection.
 */
public class ChatNotConnectedException(
    message: String = "No active OpenCode Server connection",
) : IllegalStateException(message)
