package org.opencodemobile.shared.application.interaction

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.opencodemobile.shared.domain.cache.CacheMutationNotAllowedException
import org.opencodemobile.shared.domain.cache.MutationGate
import org.opencodemobile.shared.domain.interaction.OpenCodeInteractionGateway
import org.opencodemobile.shared.domain.interaction.PendingQuestion

/**
 * State of the pending-question surface (V1-07; `docs/ARCHITECTURE.md` §3.3).
 *
 * [questions] mirrors what the server reports. There is no local copy and no
 * "dismiss" operation: a question exists until the server stops listing it.
 */
public data class PendingQuestionsState(
    public val questions: List<PendingQuestion> = emptyList(),
    public val loading: Boolean = false,
    public val error: String? = null,
) {
    /** True while the server is waiting on at least one question. */
    public val hasPending: Boolean
        get() = questions.isNotEmpty()

    /**
     * A pending question blocks the turn, exactly like a pending permission:
     * the composer must stay disabled and the affordance is not dismissable.
     */
    public val blocksTurn: Boolean
        get() = hasPending
}

/**
 * Owns the pending-question list and the only two actions the user is allowed:
 * answer or reject (V1-07).
 *
 * **Survives a kill.** The list is not persisted anywhere: [refresh] re-reads
 * `GET /question` on start and on every reconnect, so a process kill loses
 * nothing. Re-reading the server is the single source of truth.
 *
 * **Non-dismissable.** The class exposes no way to clear a question without a
 * server round-trip; [state] only changes when the server answers.
 *
 * Mutations are gated by [MutationGate]: offline, the actions fail and nothing
 * is queued (D8).
 */
public class PendingQuestionsController(
    private val gateway: OpenCodeInteractionGateway,
    private val mutationGate: MutationGate,
) {
    private val _state = MutableStateFlow(PendingQuestionsState())

    public val state: StateFlow<PendingQuestionsState> = _state.asStateFlow()

    /** Re-reads the pending questions from the server. Call on start and reconnect. */
    public suspend fun refresh(directory: String? = null): Result<Unit> {
        _state.value = _state.value.copy(loading = true, error = null)
        return runCatchingNonCancellable { gateway.pendingQuestions(directory) }.fold(
            onSuccess = { questions ->
                _state.value = PendingQuestionsState(questions = questions)
                Result.success(Unit)
            },
            onFailure = { failure ->
                _state.value = _state.value.copy(loading = false, error = failure.messageOrType())
                Result.failure(failure)
            },
        )
    }

    /**
     * Answers [requestId] with one list of selected labels per asked question,
     * then re-reads the server so the state reflects what the server now holds.
     */
    public suspend fun answer(
        requestId: String,
        answers: List<List<String>>,
        directory: String? = null,
    ): Result<Unit> = mutate(directory) { gateway.answerQuestion(requestId, answers, directory) }

    /** Rejects [requestId], then re-reads the server state. */
    public suspend fun reject(requestId: String, directory: String? = null): Result<Unit> =
        mutate(directory) { gateway.rejectQuestion(requestId, directory) }

    private suspend fun mutate(directory: String?, block: suspend () -> Unit): Result<Unit> {
        if (!mutationGate.mutationsAllowed()) {
            return Result.failure(CacheMutationNotAllowedException())
        }
        return runCatchingNonCancellable(block).fold(
            onSuccess = { refresh(directory) },
            onFailure = { failure ->
                _state.value = _state.value.copy(error = failure.messageOrType())
                Result.failure(failure)
            },
        )
    }
}
