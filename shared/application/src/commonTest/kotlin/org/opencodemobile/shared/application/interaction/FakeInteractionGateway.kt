package org.opencodemobile.shared.application.interaction

import org.opencodemobile.shared.domain.interaction.OpenCodeInteractionGateway
import org.opencodemobile.shared.domain.interaction.PendingQuestion
import org.opencodemobile.shared.domain.interaction.ServerCatalog

/**
 * In-memory [OpenCodeInteractionGateway] for the application-layer tests.
 *
 * The real implementation is `OpenCodeV2Adapter` in `shared/networking`, which
 * `shared:application` may not depend on (architecture rule §5.2), so the
 * controllers are exercised against this fake and the adapter is covered by its
 * own integration test against `MockOpenCodeServer`.
 */
internal class FakeInteractionGateway(
    var questions: List<PendingQuestion> = emptyList(),
    var catalog: ServerCatalog = ServerCatalog(),
) : OpenCodeInteractionGateway {

    val answered: MutableList<Pair<String, List<List<String>>>> = mutableListOf()
    val rejected: MutableList<String> = mutableListOf()
    val aborted: MutableList<String> = mutableListOf()

    /** Number of `GET /question` reads, so a test can prove refresh cadence. */
    var pendingQuestionsReads: Int = 0
        private set

    var pendingQuestionsError: Throwable? = null
    var answerError: Throwable? = null
    var rejectError: Throwable? = null
    var abortError: Throwable? = null
    var catalogError: Throwable? = null

    var lastDirectory: String? = null
        private set

    override suspend fun pendingQuestions(directory: String?): List<PendingQuestion> {
        lastDirectory = directory
        pendingQuestionsReads += 1
        pendingQuestionsError?.let { throw it }
        return questions
    }

    override suspend fun answerQuestion(requestId: String, answers: List<List<String>>, directory: String?) {
        lastDirectory = directory
        answerError?.let { throw it }
        answered += requestId to answers
        // A real server removes the answered question from `GET /question`.
        questions = questions.filterNot { it.id == requestId }
    }

    override suspend fun rejectQuestion(requestId: String, directory: String?) {
        lastDirectory = directory
        rejectError?.let { throw it }
        rejected += requestId
        questions = questions.filterNot { it.id == requestId }
    }

    override suspend fun abortTurn(sessionId: String, directory: String?) {
        lastDirectory = directory
        abortError?.let { throw it }
        aborted += sessionId
    }

    override suspend fun serverCatalog(directory: String?): ServerCatalog {
        lastDirectory = directory
        catalogError?.let { throw it }
        return catalog
    }
}
