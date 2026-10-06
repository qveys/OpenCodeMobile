package org.opencodemobile.features.questions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.opencodemobile.shared.application.interaction.PendingQuestionsState
import org.opencodemobile.shared.domain.interaction.PendingQuestion
import org.opencodemobile.shared.domain.interaction.QuestionItem
import org.opencodemobile.shared.domain.interaction.QuestionOption

/** V1-07: the UI projection mirrors the server state and never invents a reply. */
class QuestionsUiStateTest {

    private val question = PendingQuestion(
        id = "que_1",
        sessionId = "ses_1",
        questions = listOf(
            QuestionItem(
                question = "Which branch should I rebase onto?",
                header = "Rebase target",
                options = listOf(QuestionOption("main"), QuestionOption("release")),
            ),
        ),
    )

    @Test
    fun theUiStateMirrorsTheServerQuestionsAndBlocksTheTurn() {
        val ui = QuestionsUiState.from(PendingQuestionsState(questions = listOf(question)))

        assertTrue(ui.hasPending)
        assertTrue(ui.blocksTurn, "an open question blocks the turn")
        val mapped = ui.questions.single()
        assertEquals("que_1", mapped.requestId)
        assertEquals("Rebase target", mapped.items.single().header)
        assertEquals(listOf("main", "release"), mapped.items.single().options.map { it.label })
    }

    @Test
    fun anEmptyServerListBlocksNothing() {
        val ui = QuestionsUiState.from(PendingQuestionsState())

        assertFalse(ui.hasPending)
        assertFalse(ui.blocksTurn)
    }

    @Test
    fun theReplyEchoesOnlyLabelsTheServerOfferedPlusCustomTextWhenAllowed() {
        val single = question.questions.single().toUiPublic()
        assertEquals(listOf("main"), single.answer(setOf("main")))
        assertEquals(emptyList(), single.answer(setOf("not-an-option")))

        val custom = QuestionItem(
            question = "Anything else?",
            header = "Notes",
            custom = true,
        ).toUiPublic()
        assertEquals(listOf("a note"), custom.answer(emptySet(), "  a note  "))
        assertTrue(custom.hasAnswer(emptySet(), "a note"))
        assertFalse(custom.hasAnswer(emptySet(), "   "))
    }
}

private fun QuestionItem.toUiPublic(): QuestionItemUi = QuestionItemUi(
    header = header,
    question = question,
    options = options.map { QuestionOptionUi(it.label, it.description) },
    multiple = multiple,
    custom = custom,
)
