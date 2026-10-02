package org.opencodemobile.android.questions

import org.opencodemobile.shared.domain.event.EventSource
import org.opencodemobile.shared.domain.interaction.InteractionNotConnectedException
import org.opencodemobile.shared.domain.interaction.OpenCodeInteractionGateway
import org.opencodemobile.shared.domain.interaction.PendingQuestion
import org.opencodemobile.shared.domain.interaction.ServerCatalog

/**
 * The active server connection the pending-question surface is scoped to (V1-07).
 *
 * The app shell has no connection/onboarding composition root yet, so this seam
 * lets the question graph be assembled today against an **optional** connection:
 * the connection composition root binds a `QuestionConnection` once it exists,
 * and `questionsModule` stays inert (fail-closed) until then. It mirrors
 * `PermissionConnection` / `ChatConnection` and should be unified with them when
 * the connection root lands (PR #30 / OPE-154).
 *
 * `gateway` is the real `OpenCodeV2Adapter` interaction surface; the seam never
 * re-implements networking, and the UI never gets an HTTP path. `source` is the
 * started `EventProcessor`, used to refresh on every return to `Live`.
 */
public interface QuestionConnection {
    /** The real `GET /question` + reply/reject interaction surface. */
    public val gateway: OpenCodeInteractionGateway

    /** The per-connection realtime pipeline the bridge observes. */
    public val source: EventSource

    /** The active project root the questions are scoped to, or null for the default. */
    public val directory: String?
}

/**
 * Fail-closed [OpenCodeInteractionGateway] used while no connection is wired.
 *
 * It deliberately **throws** rather than returning an empty question list. The
 * surface has no local persistence, so an empty list is harmless, but throwing
 * keeps a wiring mistake visible instead of pretending the server answered "no
 * questions". With no connection the surface is inert, so this is never reached
 * in the normal app flow.
 */
internal object UnavailableInteractionGateway : OpenCodeInteractionGateway {
    private fun noConnection(): Nothing =
        throw InteractionNotConnectedException("no server connection is wired to the question surface")

    override suspend fun pendingQuestions(directory: String?): List<PendingQuestion> = noConnection()

    override suspend fun answerQuestion(
        requestId: String,
        answers: List<List<String>>,
        directory: String?,
    ): Unit = noConnection()

    override suspend fun rejectQuestion(requestId: String, directory: String?): Unit = noConnection()

    override suspend fun abortTurn(sessionId: String, directory: String?): Unit = noConnection()

    override suspend fun serverCatalog(directory: String?): ServerCatalog = noConnection()
}

/**
 * An [OpenCodeInteractionGateway] that resolves the active [QuestionConnection]
 * **at call time** instead of at Koin resolution time.
 *
 * `questionsModule`'s graph is forced on the application thread at `onCreate`. A
 * connection composition root that registers `QuestionConnection` afterwards
 * would otherwise freeze the surface on [UnavailableInteractionGateway] forever.
 * Resolving per call lets the surface reach the connection as soon as it exists,
 * and still fails closed while it does not.
 */
internal class DeferredInteractionGateway(
    private val resolveConnection: () -> QuestionConnection?,
) : OpenCodeInteractionGateway {
    private fun gateway(): OpenCodeInteractionGateway =
        resolveConnection()?.gateway ?: UnavailableInteractionGateway

    override suspend fun pendingQuestions(directory: String?): List<PendingQuestion> =
        gateway().pendingQuestions(directory)

    override suspend fun answerQuestion(
        requestId: String,
        answers: List<List<String>>,
        directory: String?,
    ): Unit = gateway().answerQuestion(requestId, answers, directory)

    override suspend fun rejectQuestion(requestId: String, directory: String?): Unit =
        gateway().rejectQuestion(requestId, directory)

    override suspend fun abortTurn(sessionId: String, directory: String?): Unit =
        gateway().abortTurn(sessionId, directory)

    override suspend fun serverCatalog(directory: String?): ServerCatalog =
        gateway().serverCatalog(directory)
}
