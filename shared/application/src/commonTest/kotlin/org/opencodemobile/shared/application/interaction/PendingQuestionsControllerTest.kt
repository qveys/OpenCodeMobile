package org.opencodemobile.shared.application.interaction

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.opencodemobile.shared.domain.cache.ConnectionState
import org.opencodemobile.shared.domain.interaction.PendingQuestion
import org.opencodemobile.shared.domain.interaction.FormAnswer
import org.opencodemobile.shared.domain.interaction.FormField
import org.opencodemobile.shared.domain.interaction.FormFieldType
import org.opencodemobile.shared.domain.interaction.FormValue
import org.opencodemobile.shared.domain.interaction.FormOption
import org.opencodemobile.shared.domain.cache.ConnectivityMutationGate

class PendingQuestionsControllerTest {

    private val question = PendingQuestion(
        id = "que_1",
        sessionId = "ses_1",
        title = "Rebase target",
        fields = listOf(
            FormField(
                key = "branch",
                type = FormFieldType.Multiselect,
                title = "Which branch?",
                options = listOf(FormOption("main", "main"), FormOption("release", "release")),
            ),
        ),
    )

    @Test
    fun refreshExposesTheQuestionsTheServerReports() = runTest {
        val gateway = FakeInteractionGateway(questions = listOf(question))
        val controller = PendingQuestionsController(gateway, onlineGate())

        val outcome = controller.refresh()

        assertTrue(outcome.isSuccess)
        assertEquals(listOf(question), controller.state.value.questions)
        assertTrue(controller.state.value.hasPending)
        assertTrue(controller.state.value.blocksTurn, "an open question blocks the turn")
    }

    @Test
    fun answeringSendsTheSelectedLabelsAndReReadsTheServer() = runTest {
        val gateway = FakeInteractionGateway(questions = listOf(question))
        val controller = PendingQuestionsController(gateway, onlineGate())
        controller.refresh()

        val answer: FormAnswer = mapOf("branch" to FormValue.Choices(listOf("main")))
        val outcome = controller.answer("ses_1", "que_1", answer)

        assertTrue(outcome.isSuccess)
        assertEquals(listOf(Triple("ses_1", "que_1", answer)), gateway.answered)
        assertTrue(controller.state.value.questions.isEmpty(), "the answered question is gone")
        assertFalse(controller.state.value.blocksTurn)
    }

    @Test
    fun rejectingRemovesTheQuestionWhenTheServerStopsListingIt() = runTest {
        val gateway = FakeInteractionGateway(questions = listOf(question))
        val controller = PendingQuestionsController(gateway, onlineGate())
        controller.refresh()

        val outcome = controller.reject("ses_1", "que_1")

        assertTrue(outcome.isSuccess)
        assertEquals(listOf("ses_1" to "que_1"), gateway.rejected)
        assertTrue(controller.state.value.questions.isEmpty())
    }

    @Test
    fun actionsAreRefusedOfflineAndNothingIsSent() = runTest {
        val gateway = FakeInteractionGateway(questions = listOf(question))
        val controller = PendingQuestionsController(gateway, offlineGate())

        val answerOutcome = controller.answer("ses_1", "que_1", mapOf("branch" to FormValue.Choices(listOf("main"))))
        val rejectOutcome = controller.reject("ses_1", "que_1")

        assertTrue(answerOutcome.isFailure, "offline answer must fail (D8)")
        assertTrue(rejectOutcome.isFailure, "offline reject must fail (D8)")
        assertTrue(gateway.answered.isEmpty())
        assertTrue(gateway.rejected.isEmpty())
    }

    @Test
    fun pendingQuestionsSurviveAKillBecauseTheServerIsTheSourceOfTruth() = runTest {
        val gateway = FakeInteractionGateway(questions = listOf(question))

        // First "process": the question is read from the server.
        val before = PendingQuestionsController(gateway, onlineGate())
        before.refresh()
        assertEquals(1, before.state.value.questions.size)

        // The process is killed and restarted: a brand-new controller over the
        // same server re-reads `GET /question` and still sees the question.
        val after = PendingQuestionsController(gateway, onlineGate())
        after.refresh()

        assertEquals(
            listOf(question),
            after.state.value.questions,
            "a kill loses nothing: the server still holds the pending question",
        )
    }

    @Test
    fun aTransportFailureIsSurfacedWithoutInventingQuestions() = runTest {
        val gateway = FakeInteractionGateway(questions = listOf(question)).apply {
            pendingQuestionsError = IllegalStateException("network down")
        }
        val controller = PendingQuestionsController(gateway, onlineGate())

        val outcome = controller.refresh()

        assertTrue(outcome.isFailure)
        assertTrue(controller.state.value.questions.isEmpty())
        assertEquals("network down", controller.state.value.error)
    }

    private fun onlineGate() = ConnectivityMutationGate(ConnectionState.Online)

    private fun offlineGate() = ConnectivityMutationGate(ConnectionState.Offline)
}
