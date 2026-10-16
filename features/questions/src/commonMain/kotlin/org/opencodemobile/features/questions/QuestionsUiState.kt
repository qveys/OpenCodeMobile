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
import org.opencodemobile.shared.domain.interaction.FormField
import org.opencodemobile.shared.domain.interaction.FormFieldType
import org.opencodemobile.shared.domain.interaction.FormValue
import org.opencodemobile.shared.domain.interaction.FormAnswer

/** One server form, preserving its field keys and option values. */
public data class PendingQuestionUi(
    public val requestId: String,
    public val sessionId: String,
    public val title: String,
    public val fields: List<FormField>,
)

/** Converts local input to a typed value; invalid or empty input stays absent. */
public fun FormField.answer(text: String = "", selected: Set<String> = emptySet(), toggled: Boolean = false): FormValue? =
    when (type) {
        FormFieldType.Text -> text.trim().takeIf { it.isNotEmpty() }?.let { FormValue.Text(it) }
        FormFieldType.Number, FormFieldType.Integer -> text.trim().toDoubleOrNull()
            ?.takeIf { it.isFinite() && (type != FormFieldType.Integer || it % 1.0 == 0.0) }
            ?.let { FormValue.Number(it) }
        FormFieldType.Toggle -> FormValue.Toggle(toggled)
        FormFieldType.Multiselect -> {
            val values = options.map { it.value }.filter { it in selected } +
                if (custom && text.isNotBlank()) listOf(text.trim()) else emptyList()
            values.takeIf { it.isNotEmpty() }?.let { FormValue.Choices(it) }
        }
        FormFieldType.External -> null
    }

/** External fields are completed outside the form; malformed numeric input cannot be sent. */
public fun FormField.canReply(text: String = "", selected: Set<String> = emptySet(), toggled: Boolean = false): Boolean {
    if (type == FormFieldType.External) return true
    val value = answer(text, selected, toggled)
    return (!required || value != null) &&
        (type !in listOf(FormFieldType.Number, FormFieldType.Integer) || text.isBlank() || value != null)
}

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
    title = title,
    fields = fields,
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

    /** Re-reads `GET /api/form`. Called on start and on every `Live` return. */
    public fun refresh(directory: String? = null) {
        scope.launch { controller.refresh(directory) }
    }

    /** Answers [requestId] with typed values keyed by field. */
    public fun answer(sessionId: String, requestId: String, answer: FormAnswer, directory: String? = null) {
        scope.launch { controller.answer(sessionId, requestId, answer, directory) }
    }

    /** Rejects [requestId]. The question stays visible until the server drops it. */
    public fun reject(sessionId: String, requestId: String, directory: String? = null) {
        scope.launch { controller.reject(sessionId, requestId, directory) }
    }
}
