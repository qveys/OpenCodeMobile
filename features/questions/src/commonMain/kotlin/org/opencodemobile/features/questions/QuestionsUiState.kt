package org.opencodemobile.features.questions

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.opencodemobile.shared.application.interaction.PendingQuestionsController
import org.opencodemobile.shared.application.interaction.PendingQuestionsState
import org.opencodemobile.shared.domain.interaction.PendingQuestion
import org.opencodemobile.shared.domain.interaction.QuestionItem
import org.opencodemobile.shared.domain.interaction.QuestionOption

/** One selectable label offered for a question, exactly as the server exposed it. */
public data class QuestionOptionUi(
    /** The label the reply must echo back in `answers`. */
    public val label: String,
    public val description: String? = null,
)

/** One question inside a pending request, exactly as the server exposed it. */
public data class QuestionItemUi(
    public val header: String,
    public val question: String,
    public val options: List<QuestionOptionUi> = emptyList(),
    /** True when the user may select several options at once. */
    public val multiple: Boolean = false,
    /** True when the user may type a free-form answer in addition to the options. */
    public val custom: Boolean = false,
) {
    /**
     * Builds the server reply for this question: the selected labels in server
     * order, plus the trimmed free-form text when the question allows one. Never
     * invents a label the server did not offer.
     */
    public fun answer(selectedLabels: Collection<String>, customText: String = ""): List<String> {
        val known = options.map { it.label }
        val selected = known.filter { it in selectedLabels }
        val free = customText.trim()
        return if (custom && free.isNotEmpty()) selected + free else selected
    }

    /** Whether [selectedLabels] / [customText] form a usable answer. */
    public fun hasAnswer(selectedLabels: Collection<String>, customText: String = ""): Boolean =
        answer(selectedLabels, customText).isNotEmpty()
}

/** One pending request: an id to answer/reject, its session, and its questions. */
public data class PendingQuestionUi(
    public val requestId: String,
    public val sessionId: String,
    public val items: List<QuestionItemUi>,
)

/**
 * What the pending-question surface renders. It is a faithful projection of the
 * server state: nothing is summarized, and there is no local "dismissed" flag.
 */
public data class QuestionsUiState(
    public val questions: List<PendingQuestionUi> = emptyList(),
    public val loading: Boolean = false,
    public val error: String? = null,
) {
    /** True while the server is waiting on at least one question. */
    public val hasPending: Boolean
        get() = questions.isNotEmpty()

    /**
     * A pending question blocks the turn: the composer must stay disabled and
     * the affordance is not dismissable (V1-07).
     */
    public val blocksTurn: Boolean
        get() = hasPending

    public companion object {
        public fun from(state: PendingQuestionsState): QuestionsUiState = QuestionsUiState(
            questions = state.questions.map { it.toUi() },
            loading = state.loading,
            error = state.error,
        )
    }
}

private fun PendingQuestion.toUi(): PendingQuestionUi = PendingQuestionUi(
    requestId = id,
    sessionId = sessionId,
    items = questions.map { it.toUi() },
)

private fun QuestionItem.toUi(): QuestionItemUi = QuestionItemUi(
    header = header,
    question = question,
    options = options.map { QuestionOptionUi(label = it.label, description = it.description) },
    multiple = multiple,
    custom = custom,
)

/**
 * State holder the Compose layer observes.
 *
 * It is intentionally thin: the server-is-source-of-truth rule, the offline gate
 * and the exact answer/reject relay live in [PendingQuestionsController]; the
 * presenter only maps state to the view model and forwards user intents. There is
 * deliberately no `dismiss`.
 */
public class QuestionsPresenter(
    private val controller: PendingQuestionsController,
    private val scope: CoroutineScope,
) {
    public val state: StateFlow<QuestionsUiState> =
        controller.state
            .map { QuestionsUiState.from(it) }
            .stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = QuestionsUiState.from(controller.state.value),
            )

    /** Re-reads `GET /question`. Called on start and on every `Live` return. */
    public fun refresh(directory: String? = null) {
        scope.launch { controller.refresh(directory) }
    }

    /** Answers [requestId] with one list of labels per asked question. */
    public fun answer(requestId: String, answers: List<List<String>>, directory: String? = null) {
        scope.launch { controller.answer(requestId, answers, directory) }
    }

    /** Rejects [requestId]. The question stays visible until the server drops it. */
    public fun reject(requestId: String, directory: String? = null) {
        scope.launch { controller.reject(requestId, directory) }
    }
}
