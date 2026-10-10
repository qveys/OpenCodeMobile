package org.opencodemobile.features.questions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.opencodemobile.design.system.LocalOpenCodeColors
import org.opencodemobile.design.system.OpenCodeMetrics
import org.opencodemobile.design.system.OpenCodeSpacing
import org.opencodemobile.design.system.OpenCodeType
import org.opencodemobile.design.system.resources.Res
import org.opencodemobile.design.system.resources.ui_answer
import org.opencodemobile.design.system.resources.question_reject
import org.opencodemobile.design.system.resources.question_reply

/**
 * The pending-question surface (V1-07, `docs/ARCHITECTURE.md` §3.3).
 *
 * It renders every question `GET /question` returned and offers exactly the two
 * allowed actions: **Reply** and **Reject**. It is **not dismissable**: there is
 * no close control, no swipe wrapper, and no callback that clears a question —
 * only the server can make it go away, by dropping it from `GET /question`.
 *
 * The screen is stateless with respect to the domain: it renders
 * [QuestionsUiState] and forwards intents. The option selection and free-form
 * text are local UI state; the reply it builds echoes only labels the server
 * offered (plus the trimmed custom text when the question allows one).
 *
 * While this surface shows a question the caller must keep the composer
 * disabled: [QuestionsUiState.blocksTurn] is the flag to consume.
 */
@Composable
public fun PendingQuestionsScreen(
    state: QuestionsUiState,
    onReply: (requestId: String, answers: List<List<String>>) -> Unit,
    onReject: (requestId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.hasPending) return
    val colors = LocalOpenCodeColors.current
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Assertive },
        color = colors.bgRaised,
        contentColor = colors.text,
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(OpenCodeSpacing.x4),
            verticalArrangement = Arrangement.spacedBy(OpenCodeSpacing.x3),
        ) {
            Text(
                text = "[?] The agent is waiting on your answer",
                style = OpenCodeType.section,
            )
            state.error?.let { error ->
                Text(text = error, style = OpenCodeType.meta, color = colors.danger)
            }
            for (question in state.questions) {
                PendingQuestionCard(
                    question = question,
                    onReply = onReply,
                    onReject = onReject,
                )
            }
        }
    }
}

@Composable
private fun PendingQuestionCard(
    question: PendingQuestionUi,
    onReply: (String, List<List<String>>) -> Unit,
    onReject: (String) -> Unit,
) {
    val colors = LocalOpenCodeColors.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(OpenCodeSpacing.x2),
    ) {
        val selections: List<QuestionAnswerState> = question.items.map { item ->
            rememberQuestionAnswerState(item)
        }
        for ((index, item) in question.items.withIndex()) {
            val answer = selections[index]
            Text(text = item.header, style = OpenCodeType.bodyStrong)
            Text(text = item.question, style = OpenCodeType.body)
            for (option in item.options) {
                QuestionOptionRow(
                    option = option,
                    multiple = item.multiple,
                    selected = option.label in answer.selected,
                    onToggle = { answer.toggle(option.label) },
                )
            }
            if (item.custom) {
                OutlinedTextField(
                    value = answer.customText,
                    onValueChange = { answer.customText = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = OpenCodeType.body,
                    label = { Text(stringResource(Res.string.ui_answer), style = OpenCodeType.body) },
                    maxLines = 4,
                )
            }
            HorizontalDivider(color = colors.line, thickness = OpenCodeMetrics.hairline)
        }

        val canReply = question.items.indices.all { index ->
            question.items[index].hasAnswer(
                selections[index].selected,
                selections[index].customText,
            )
        }
        QuestionActions(
            canReply = canReply,
            onReply = {
                val answers = question.items.mapIndexed { index, item ->
                    item.answer(selections[index].selected, selections[index].customText)
                }
                onReply(question.requestId, answers)
            },
            onReject = { onReject(question.requestId) },
        )
    }
}

@Composable
private fun QuestionActions(canReply: Boolean, onReply: () -> Unit, onReject: () -> Unit) {
    val colors = LocalOpenCodeColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OpenCodeSpacing.x2),
    ) {
        Button(
            onClick = onReply,
            enabled = canReply,
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.primary,
                contentColor = colors.onPrimary,
            ),
        ) {
            Text(stringResource(Res.string.question_reply), style = OpenCodeType.control)
        }
        OutlinedButton(onClick = onReject) {
            Text(stringResource(Res.string.question_reject), style = OpenCodeType.control)
        }
    }
}

/** Local (non-domain) selection state for one asked question. */
private class QuestionAnswerState {
    var selected: Set<String> by mutableStateOf(emptySet())
    var customText: String by mutableStateOf("")

    fun toggle(label: String) {
        selected = if (label in selected) selected - label else selected + label
    }
}

@Composable
private fun rememberQuestionAnswerState(item: QuestionItemUi): QuestionAnswerState =
    remember(item.header, item.question) { QuestionAnswerState() }

@Composable
private fun QuestionOptionRow(
    option: QuestionOptionUi,
    multiple: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    val colors = LocalOpenCodeColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (multiple) {
            Checkbox(checked = selected, onCheckedChange = null)
        } else {
            RadioButton(selected = selected, onClick = null)
        }
        Column(modifier = Modifier.padding(start = OpenCodeSpacing.x1)) {
            Text(text = option.label, style = OpenCodeType.body)
            option.description?.let { description ->
                Text(text = description, style = OpenCodeType.meta, color = colors.textMuted)
            }
        }
    }
}
