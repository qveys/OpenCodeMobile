package org.opencodemobile.shared.application.interaction

import org.opencodemobile.shared.domain.interaction.OpenCodeInteractionGateway
import org.opencodemobile.shared.domain.interaction.PendingQuestion
import org.opencodemobile.shared.domain.interaction.ServerCatalog
import org.opencodemobile.shared.domain.interaction.FormAnswer

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

    val answered: MutableList<Triple<String, String, FormAnswer>> = mutableListOf()
    val rejected: MutableList<Pair<String, String>> = mutableListOf()
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

    override suspend fun answerQuestion(sessionId: String, requestId: String, answer: FormAnswer, directory: String?) {
        lastDirectory = directory
        answerError?.let { throw it }
        answered += Triple(sessionId, requestId, answer)
        // A real server removes the answered question from `GET /question`.
        questions = questions.filterNot { it.id == requestId }
    }

    override suspend fun rejectQuestion(sessionId: String, requestId: String, directory: String?) {
        lastDirectory = directory
        rejectError?.let { throw it }
        rejected += sessionId to requestId
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
