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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
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
import org.opencodemobile.design.system.resources.question_reject
import org.opencodemobile.design.system.resources.question_reply
import org.opencodemobile.design.system.resources.question_waiting
import org.opencodemobile.design.system.resources.ui_answer
import org.opencodemobile.shared.domain.interaction.FormAnswer
import org.opencodemobile.shared.domain.interaction.FormFieldType

/** Displays server forms and sends typed answers using their field keys. */
@Composable
public fun PendingQuestionsScreen(
    state: QuestionsUiState,
    onReply: (sessionId: String, requestId: String, answer: FormAnswer) -> Unit,
    onReject: (sessionId: String, requestId: String) -> Unit,
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
                text = stringResource(Res.string.question_waiting),
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
    onReply: (String, String, FormAnswer) -> Unit,
    onReject: (String, String) -> Unit,
) {
    val colors = LocalOpenCodeColors.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(OpenCodeSpacing.x2),
    ) {
        Text(text = question.title, style = OpenCodeType.bodyStrong)
        val selections = remember(question.requestId, question.sessionId, question.fields) {
            question.fields.map { QuestionAnswerState() }
        }
        val uriHandler = LocalUriHandler.current
        for ((index, field) in question.fields.withIndex()) {
            val input = selections[index]
            Text(text = (field.title ?: field.key) + if (field.required) " *" else "", style = OpenCodeType.bodyStrong)
            field.description?.let { Text(text = it, style = OpenCodeType.body) }
            when (field.type) {
                FormFieldType.Toggle -> Checkbox(checked = input.toggled, onCheckedChange = { input.toggled = it })
                FormFieldType.External -> field.url?.let { url ->
                    OutlinedButton(onClick = { runCatching { uriHandler.openUri(url) } }) { Text(url) }
                }
                FormFieldType.Multiselect -> {
                    for (option in field.options) {
                        Row(
                            modifier = Modifier.fillMaxWidth().selectable(
                                selected = option.value in input.selected,
                                onClick = { input.toggle(option.value) },
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = option.value in input.selected, onCheckedChange = null)
                            Column {
                                Text(option.label, style = OpenCodeType.body)
                                option.description?.let { Text(it, style = OpenCodeType.meta) }
                            }
                        }
                    }
                    if (field.custom) AnswerText(input)
                }
                FormFieldType.Text, FormFieldType.Number, FormFieldType.Integer -> AnswerText(input)
            }
            HorizontalDivider(color = colors.line, thickness = OpenCodeMetrics.hairline)
        }
        val answer: FormAnswer = question.fields.mapIndexedNotNull { index, field ->
            val input = selections[index]
            field.answer(input.customText, input.selected, input.toggled)?.let { field.key to it }
        }.toMap()
        val canReply = question.fields.indices.all { index ->
            val input = selections[index]
            question.fields[index].canReply(input.customText, input.selected, input.toggled)
        }
        QuestionActions(
            canReply = canReply,
            onReply = { onReply(question.sessionId, question.requestId, answer) },
            onReject = { onReject(question.sessionId, question.requestId) },
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
    var toggled: Boolean by mutableStateOf(false)
    var selected: Set<String> by mutableStateOf(emptySet())
    var customText: String by mutableStateOf("")

    fun toggle(label: String) {
        selected = if (label in selected) selected - label else selected + label
    }
}

@Composable
private fun AnswerText(input: QuestionAnswerState) {
    OutlinedTextField(
        value = input.customText,
        onValueChange = { input.customText = it },
        modifier = Modifier.fillMaxWidth(),
        textStyle = OpenCodeType.body,
        label = { Text(stringResource(Res.string.ui_answer)) },
        maxLines = 4,
    )
}
