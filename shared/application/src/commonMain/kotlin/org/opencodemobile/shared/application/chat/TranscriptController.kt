package org.opencodemobile.shared.application.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.opencodemobile.shared.application.interaction.messageOrType
import org.opencodemobile.shared.application.interaction.runCatchingNonCancellable
import org.opencodemobile.shared.domain.chat.ChatEvent
import org.opencodemobile.shared.domain.chat.OpenCodeChatGateway
import org.opencodemobile.shared.domain.chat.TranscriptMessage
import org.opencodemobile.shared.domain.chat.TranscriptPart
import org.opencodemobile.shared.domain.chat.TranscriptRole

/** The transcript state observed by the Compose layer (V1-05). */
public data class TranscriptState(
    /** The open session, or null before [TranscriptController.open]. */
    public val sessionId: String? = null,
    /** The messages to render, in server order. */
    public val messages: List<TranscriptMessage> = emptyList(),
    public val loading: Boolean = false,
    public val error: String? = null,
) {
    public val isEmpty: Boolean
        get() = messages.isEmpty()
}

/**
 * Owns the V1-05 transcript of one session.
 *
 * The server is the only source of truth (D2):
 *
 * - [open] / [refresh] replace the local view with `GET /session/{id}/message`,
 *   so a fresh start or a reconnect reconstitutes the transcript with **no
 *   duplicate and no lost text**;
 * - [onEvent] applies decoded `message.*` events on top of that snapshot and
 *   **upserts a part by its server id** instead of appending, so a replayed or
 *   re-sent part id converges to one copy.
 *
 * It never sends anything: sending is [ComposerController]'s job.
 */
public class TranscriptController(
    private val gateway: OpenCodeChatGateway,
) {
    private val mutableState = MutableStateFlow(TranscriptState())

    /** The state the Compose layer observes (unidirectional data flow). */
    public val state: StateFlow<TranscriptState> = mutableState.asStateFlow()

    private val order: MutableList<String> = mutableListOf()
    private val byId: MutableMap<String, TranscriptMessage> = mutableMapOf()

    /** Opens [sessionId] and reconciles its transcript from the server. */
    public suspend fun open(sessionId: String): Result<Unit> {
        order.clear()
        byId.clear()
        mutableState.value = TranscriptState(sessionId = sessionId, loading = true)
        return refresh()
    }

    /** Re-reads the authoritative transcript. Idempotent for the open session. */
    public suspend fun refresh(): Result<Unit> {
        val sessionId = mutableState.value.sessionId ?: return Result.success(Unit)
        mutableState.value = mutableState.value.copy(loading = true, error = null)
        return runCatchingNonCancellable { gateway.transcript(sessionId) }.fold(
            onSuccess = { messages ->
                replaceFromServer(messages)
                mutableState.value = mutableState.value.copy(
                    messages = snapshot(),
                    loading = false,
                    error = null,
                )
                Result.success(Unit)
            },
            onFailure = { failure ->
                mutableState.value = mutableState.value.copy(
                    loading = false,
                    error = failure.messageOrType(),
                )
                Result.failure(failure)
            },
        )
    }

    /** Clears the open session (e.g. when the user leaves the session screen). */
    public fun close() {
        order.clear()
        byId.clear()
        mutableState.value = TranscriptState()
    }

    /**
     * Applies one decoded realtime event. An event that names another session is
     * ignored, so a busy server cannot inject another session's text here.
     */
    public fun onEvent(event: ChatEvent) {
        val sessionId = mutableState.value.sessionId ?: return
        when (event) {
            is ChatEvent.PartUpdated -> {
                if (event.sessionId != null && event.sessionId != sessionId) return
                applyPart(event.messageId, sessionId, event.part)
            }

            is ChatEvent.MessageUpdated -> {
                val incoming = event.message
                if (incoming.sessionId.isNotEmpty() && incoming.sessionId != sessionId) return
                // The spec's `EventMessageUpdated.properties.info` is a `Message`
                // with no `parts`: merge the identity/role into the parts already
                // streamed instead of replacing the message with a partless copy.
                val existing = byId[incoming.id]
                val merged = if (existing == null) {
                    incoming
                } else {
                    existing.copy(
                        sessionId = incoming.sessionId.ifBlank { existing.sessionId },
                        role = incoming.role,
                        parts = if (incoming.parts.isEmpty()) existing.parts else incoming.parts,
                    )
                }
                if (!byId.containsKey(merged.id)) order += merged.id
                byId[merged.id] = merged
                emit()
            }

            is ChatEvent.MessageRemoved -> {
                if (event.sessionId != null && event.sessionId != sessionId) return
                if (byId.remove(event.messageId) != null) {
                    order.remove(event.messageId)
                    emit()
                }
            }

            is ChatEvent.PartRemoved -> {
                if (event.sessionId != null && event.sessionId != sessionId) return
                val existing = byId[event.messageId] ?: return
                val remaining = existing.parts.filterNot { it.id == event.partId }
                if (remaining.size == existing.parts.size) return
                byId[event.messageId] = existing.copy(parts = remaining)
                emit()
            }
        }
    }

    private fun replaceFromServer(messages: List<TranscriptMessage>) {
        order.clear()
        byId.clear()
        messages.forEach { message ->
            if (!byId.containsKey(message.id)) order += message.id
            byId[message.id] = message
        }
    }

    private fun applyPart(messageId: String, sessionId: String, part: TranscriptPart) {
        val existing = byId[messageId]
        val updated = if (existing == null) {
            TranscriptMessage(
                id = messageId,
                sessionId = sessionId,
                role = TranscriptRole.Assistant,
                parts = listOf(part),
            )
        } else {
            val parts = existing.parts.toMutableList()
            val index = parts.indexOfFirst { it.id == part.id }
            if (index >= 0) {
                parts[index] = part
            } else {
                parts += part
            }
            existing.copy(parts = parts)
        }
        if (!byId.containsKey(messageId)) order += messageId
        byId[messageId] = updated
        emit()
    }

    private fun emit() {
        mutableState.value = mutableState.value.copy(messages = snapshot(), error = null)
    }

    private fun snapshot(): List<TranscriptMessage> = order.mapNotNull { byId[it] }
}
