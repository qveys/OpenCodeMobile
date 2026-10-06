package org.opencodemobile.shared.application.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
 *
 * ## Concurrency
 *
 * The realtime collector (`onEvent`) and the reconciliation paths
 * ([open]/[refresh]) can run on different coroutine contexts (the e2e drives them
 * on `Dispatchers.Default`). The whole view is therefore a single immutable
 * [TranscriptState] published through [MutableStateFlow.update], an atomic
 * compare-and-set: every mutation reads and writes the view in one transaction,
 * so a torn update can never duplicate an id in the message list (which would
 * produce duplicate `LazyColumn` keys).
 *
 * A snapshot fetched by [refresh] is only authoritative up to the moment it was
 * requested: if a realtime event lands while the network call is in flight, the
 * snapshot is **merged underneath** the live view instead of replacing it, so the
 * event's text is never dropped (KDoc "no lost text"). If no event arrived, the
 * snapshot still replaces the view wholesale, which is what drops a late orphan
 * from an aborted turn (V1-08).
 */
public class TranscriptController(
    private val gateway: OpenCodeChatGateway,
) {
    private val mutableState = MutableStateFlow(TranscriptState())

    /** The state the Compose layer observes (unidirectional data flow). */
    public val state: StateFlow<TranscriptState> = mutableState.asStateFlow()

    /**
     * Monotonic counter bumped for every realtime event that mutates the view.
     *
     * [refresh] samples it before the network call and compares it once the
     * snapshot arrives: any change means an event is newer than the snapshot and
     * the reconciliation must merge instead of replace. It is a separate flow
     * because the view state is public and must not expose the counter.
     */
    private val eventGeneration = MutableStateFlow(0L)

    /** Opens [sessionId] and reconciles its transcript from the server. */
    public suspend fun open(sessionId: String): Result<Unit> {
        mutableState.update { TranscriptState(sessionId = sessionId, loading = true) }
        return refresh()
    }

    /** Re-reads the authoritative transcript. Idempotent for the open session. */
    public suspend fun refresh(): Result<Unit> {
        val sessionId = mutableState.value.sessionId ?: return Result.success(Unit)
        mutableState.update {
            if (it.sessionId == sessionId) it.copy(loading = true, error = null) else it
        }
        val generationAtRequest = eventGeneration.value
        return runCatchingNonCancellable { gateway.transcript(sessionId) }.fold(
            onSuccess = { messages ->
                reconcile(sessionId, messages, generationAtRequest)
                Result.success(Unit)
            },
            onFailure = { failure ->
                mutableState.update {
                    if (it.sessionId == sessionId) {
                        it.copy(loading = false, error = failure.messageOrType())
                    } else {
                        it
                    }
                }
                Result.failure(failure)
            },
        )
    }

    /** Clears the open session (e.g. when the user leaves the session screen). */
    public fun close() {
        mutableState.update { TranscriptState() }
    }

    /**
     * Applies one decoded realtime event. An event that names another session is
     * ignored, so a busy server cannot inject another session's text here.
     */
    @Suppress("CyclomaticComplexMethod")
    public fun onEvent(event: ChatEvent) {
        when (event) {
            is ChatEvent.PartUpdated -> mutate { state, sessionId ->
                if (event.sessionId != null && event.sessionId != sessionId) return@mutate state
                state.copy(
                    messages = upsertPart(state.messages, event.messageId, sessionId, event.part),
                    error = null,
                )
            }

            is ChatEvent.MessageUpdated -> mutate { state, sessionId ->
                val incoming = event.message
                if (incoming.sessionId.isNotEmpty() && incoming.sessionId != sessionId) return@mutate state
                // The spec's `EventMessageUpdated.properties.info` is a `Message`
                // with no `parts`: merge the identity/role into the parts already
                // streamed instead of replacing the message with a partless copy.
                val existing = state.messages.firstOrNull { it.id == incoming.id }
                val merged = if (existing == null) {
                    incoming
                } else {
                    existing.copy(
                        sessionId = incoming.sessionId.ifBlank { existing.sessionId },
                        role = incoming.role,
                        parts = if (incoming.parts.isEmpty()) existing.parts else incoming.parts,
                    )
                }
                state.copy(messages = upsertMessage(state.messages, merged), error = null)
            }

            is ChatEvent.MessageRemoved -> mutate { state, sessionId ->
                if (event.sessionId != null && event.sessionId != sessionId) return@mutate state
                if (state.messages.none { it.id == event.messageId }) return@mutate state
                state.copy(messages = state.messages.filterNot { it.id == event.messageId }, error = null)
            }

            is ChatEvent.PartRemoved -> mutate { state, sessionId ->
                if (event.sessionId != null && event.sessionId != sessionId) return@mutate state
                val messageIndex = state.messages.indexOfFirst { it.id == event.messageId }
                if (messageIndex < 0) return@mutate state
                val message = state.messages[messageIndex]
                if (message.parts.none { it.id == event.partId }) return@mutate state
                val remaining = message.parts.filterNot { it.id == event.partId }
                state.copy(
                    messages = state.messages.toMutableList().also { it[messageIndex] = message.copy(parts = remaining) },
                    error = null,
                )
            }
        }
    }

    /**
     * Applies one event mutation atomically. The generation is bumped **before**
     * the view is published so a [refresh] that samples it afterwards can never
     * mistake a newer event for an older snapshot (an over-bump only forces a
     * merge, it never drops text).
     */
    private inline fun mutate(transform: (TranscriptState, String) -> TranscriptState) {
        val sessionId = mutableState.value.sessionId ?: return
        eventGeneration.update { it + 1 }
        mutableState.update { state ->
            if (state.sessionId != sessionId) state else transform(state, sessionId)
        }
    }

    /**
     * Reconciles a server snapshot with the live view.
     *
     * When no event arrived during the round-trip the snapshot is authoritative
     * and replaces the view (dropping a late orphan from an aborted turn). When
     * an event did arrive, the live view is newer for the data it already holds,
     * so the snapshot is merged underneath it: the two can never drop the
     * streamed text nor duplicate a message id.
     */
    private fun reconcile(sessionId: String, messages: List<TranscriptMessage>, generationAtRequest: Long) {
        val eventsArrived = eventGeneration.value != generationAtRequest
        mutableState.update { state ->
            if (state.sessionId != sessionId) return@update state
            val reconciled = if (eventsArrived) mergeWithServer(state.messages, messages) else serverOrder(messages)
            state.copy(messages = reconciled, loading = false, error = null)
        }
    }

    /** The server order, first occurrence of an id wins (no duplicate key). */
    private fun serverOrder(messages: List<TranscriptMessage>): List<TranscriptMessage> {
        val seen = HashSet<String>(messages.size)
        return messages.filter { seen.add(it.id) }
    }

    /**
     * Merges a server snapshot underneath [live]: the live view wins for the
     * messages and parts it already has (they are newer), while the snapshot
     * contributes any message or part it knows and the live view does not.
     */
    private fun mergeWithServer(
        live: List<TranscriptMessage>,
        server: List<TranscriptMessage>,
    ): List<TranscriptMessage> {
        val merged = live.toMutableList()
        val indexById = HashMap<String, Int>(merged.size)
        merged.forEachIndexed { index, message -> indexById[message.id] = index }
        server.forEach { incoming ->
            val index = indexById[incoming.id]
            if (index == null) {
                indexById[incoming.id] = merged.size
                merged += incoming
            } else {
                val current = merged[index]
                val knownPartIds = current.parts.mapTo(HashSet()) { it.id }
                val missing = incoming.parts.filter { knownPartIds.add(it.id) }
                if (missing.isNotEmpty()) merged[index] = current.copy(parts = current.parts + missing)
            }
        }
        return merged
    }

    /** Replaces the message with the same id in place, or appends it. */
    private fun upsertMessage(
        messages: List<TranscriptMessage>,
        message: TranscriptMessage,
    ): List<TranscriptMessage> {
        val index = messages.indexOfFirst { it.id == message.id }
        if (index < 0) return messages + message
        return messages.toMutableList().also { it[index] = message }
    }

    /** Upserts a part by its server id, creating its message on first sight. */
    private fun upsertPart(
        messages: List<TranscriptMessage>,
        messageId: String,
        sessionId: String,
        part: TranscriptPart,
    ): List<TranscriptMessage> {
        val messageIndex = messages.indexOfFirst { it.id == messageId }
        if (messageIndex < 0) {
            return messages + TranscriptMessage(
                id = messageId,
                sessionId = sessionId,
                role = TranscriptRole.Assistant,
                parts = listOf(part),
            )
        }
        val message = messages[messageIndex]
        val partIndex = message.parts.indexOfFirst { it.id == part.id }
        val parts = if (partIndex < 0) {
            message.parts + part
        } else {
            message.parts.toMutableList().also { it[partIndex] = part }
        }
        return messages.toMutableList().also { it[messageIndex] = message.copy(parts = parts) }
    }
}
