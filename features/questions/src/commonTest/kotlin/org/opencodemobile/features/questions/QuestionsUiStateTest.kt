package org.opencodemobile.features.questions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.opencodemobile.shared.application.interaction.PendingQuestionsState
import org.opencodemobile.shared.domain.interaction.FormField
import org.opencodemobile.shared.domain.interaction.FormFieldType
import org.opencodemobile.shared.domain.interaction.FormOption
import org.opencodemobile.shared.domain.interaction.FormValue
import org.opencodemobile.shared.domain.interaction.PendingQuestion

class QuestionsUiStateTest {
    @Test
    fun theUiPreservesFormIdentityFieldsAndBlocksTheTurn() {
        val field = FormField("branch", FormFieldType.Multiselect, required = true,
            options = listOf(FormOption("main-value", "Main")))
        val ui = QuestionsUiState.from(PendingQuestionsState(questions = listOf(
            PendingQuestion("frm_1", "ses_1", "Rebase target", listOf(field)),
        )))
        assertTrue(ui.blocksTurn)
        assertEquals(PendingQuestionUi("frm_1", "ses_1", "Rebase target", listOf(field)), ui.questions.single())
        assertFalse(QuestionsUiState().hasPending)
    }

    @Test
    fun choicesEchoValuesInServerOrderAndAllowOnlyExplicitCustomInput() {
        val field = FormField("branch", FormFieldType.Multiselect,
            options = listOf(FormOption("main-value", "Main"), FormOption("release-value", "Release")))
        assertEquals(FormValue.Choices(listOf("main-value", "release-value")),
            field.answer(selected = setOf("release-value", "main-value", "unknown")))
        assertNull(field.answer(selected = setOf("Main")))
        assertNull(field.answer(text = "extra"))
        assertEquals(FormValue.Choices(listOf("extra")), field.copy(custom = true).answer(text = " extra "))
    }

    @Test
    fun requiredFieldsAndMalformedOptionalNumbersGateReply() {
        assertFalse(FormField("x", FormFieldType.Text, required = true).canReply())
        assertTrue(FormField("x", FormFieldType.Text).canReply())
        assertFalse(FormField("x", FormFieldType.Multiselect, required = true).canReply())
        assertFalse(FormField("x", FormFieldType.Number).canReply("bad"))
        assertFalse(FormField("x", FormFieldType.Integer).canReply("1.5"))
        assertTrue(FormField("x", FormFieldType.Number).canReply())
        assertTrue(FormField("x", FormFieldType.Toggle, required = true).canReply(toggled = false))
        assertTrue(FormField("x", FormFieldType.External, required = true).canReply())
    }

    @Test
    fun typedValuesRejectInvalidNumbersAndPreserveFalseAndZero() {
        assertEquals(FormValue.Text("hello"), FormField("x", FormFieldType.Text).answer(" hello "))
        assertNull(FormField("x", FormFieldType.Text).answer(" "))
        assertEquals(FormValue.Number(1.5), FormField("x", FormFieldType.Number).answer("1.5"))
        assertNull(FormField("x", FormFieldType.Number).answer("NaN"))
        assertNull(FormField("x", FormFieldType.Number).answer("Infinity"))
        assertNull(FormField("x", FormFieldType.Number).answer("bad"))
        assertNull(FormField("x", FormFieldType.Integer).answer("1.5"))
        assertEquals(FormValue.Number(0.0), FormField("x", FormFieldType.Integer).answer("0"))
        assertEquals(FormValue.Toggle(false), FormField("x", FormFieldType.Toggle).answer())
        assertNull(FormField("x", FormFieldType.External, required = true).answer())
    }
}
