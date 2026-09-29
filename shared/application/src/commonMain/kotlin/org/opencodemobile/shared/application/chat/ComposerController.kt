package org.opencodemobile.shared.application.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.opencodemobile.shared.application.interaction.messageOrType
import org.opencodemobile.shared.application.interaction.runCatchingNonCancellable
import org.opencodemobile.shared.domain.cache.CacheMutationNotAllowedException
import org.opencodemobile.shared.domain.cache.MutationGate
import org.opencodemobile.shared.domain.chat.ChatPrompt
import org.opencodemobile.shared.domain.chat.ComposerDraftStore
import org.opencodemobile.shared.domain.chat.OpenCodeChatGateway

/** The composer state observed by the Compose layer (V1-05). */
public data class ComposerState(
    public val sessionId: String? = null,
    /** The local draft. Never sent until the user explicitly sends it (§8.1). */
    public val draft: String = "",
    public val sending: Boolean = false,
    public val error: String? = null,
) {
    public val canSend: Boolean
        get() = draft.isNotBlank() && !sending
}

/**
 * Owns the V1-05 composer: the prompt draft and the single send action.
 *
 * Guarantees:
 * - **The draft is local and never sent automatically (§8.1).** [open] restores
 *   the persisted draft; restoring it, or typing, never calls the gateway.
 * - **A send is exactly once (D9).** [send] calls the gateway once, on the
 *   caller's coroutine. It never queues, retries, or replays the prompt, so a
 *   reconnect cannot re-send it. On success the draft is cleared.
 * - **Offline is read-only (D8).** A send is refused before touching the wire
 *   when [MutationGate.mutationsAllowed] is false.
 */
public class ComposerController(
    private val gateway: OpenCodeChatGateway,
    private val drafts: ComposerDraftStore,
    private val mutationGate: MutationGate,
) {
    private val mutableState = MutableStateFlow(ComposerState())

    /** The state the Compose layer observes (unidirectional data flow). */
    public val state: StateFlow<ComposerState> = mutableState.asStateFlow()

    /** Opens [sessionId] and restores its draft. Never sends anything. */
    public suspend fun open(sessionId: String): Result<Unit> {
        mutableState.value = ComposerState(sessionId = sessionId)
        return runCatchingNonCancellable { drafts.loadDraft(sessionId) }.fold(
            onSuccess = { draft ->
                mutableState.value = ComposerState(sessionId = sessionId, draft = draft)
                Result.success(Unit)
            },
            onFailure = { failure ->
                mutableState.value = ComposerState(sessionId = sessionId, error = failure.messageOrType())
                Result.failure(failure)
            },
        )
    }

    /** Records a keystroke and persists the draft locally. Never sends. */
    public suspend fun updateDraft(text: String) {
        val sessionId = mutableState.value.sessionId ?: return
        mutableState.value = mutableState.value.copy(draft = text)
        runCatchingNonCancellable { drafts.saveDraft(sessionId, text) }
    }

    /**
     * Sends the current draft exactly once (D9). [agent] selects a catalog agent,
     * or null for the server default. Returns the failure without retrying.
     */
    public suspend fun send(agent: String? = null): Result<Unit> {
        val current = mutableState.value
        val sessionId = current.sessionId
            ?: return Result.failure(IllegalStateException("No session is open"))
        val text = current.draft.trim()
        if (text.isEmpty()) {
            return Result.failure(IllegalArgumentException("The prompt is empty"))
        }
        if (!mutationGate.mutationsAllowed()) {
            return Result.failure(CacheMutationNotAllowedException())
        }

        mutableState.value = current.copy(sending = true, error = null)
        return runCatchingNonCancellable {
            gateway.sendPrompt(sessionId, ChatPrompt(text = text, agent = agent))
        }.fold(
            onSuccess = {
                runCatchingNonCancellable { drafts.saveDraft(sessionId, "") }
                mutableState.value = ComposerState(sessionId = sessionId)
                Result.success(Unit)
            },
            onFailure = { failure ->
                // The draft is kept so the user can retry explicitly; nothing is
                // queued for a later transport recovery.
                mutableState.value = current.copy(sending = false, error = failure.messageOrType())
                Result.failure(failure)
            },
        )
    }

    /** Clears the open session. */
    public fun close() {
        mutableState.value = ComposerState()
    }
}
