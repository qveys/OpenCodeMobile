package org.opencodemobile.shared.domain.interaction

/**
 * The port for the V1-07/V1-08/V1-09 interaction surfaces, implemented by
 * `OpenCodeV2Adapter` in `shared/networking` (the only module allowed to touch
 * the generated client, Rule R3).
 *
 * It is deliberately separate from [org.opencodemobile.shared.domain.connection.OpenCodeGateway]:
 * connection is a lifecycle concern, while these are per-connection operations.
 * Keeping them apart lets a feature depend on exactly the interaction surface
 * it needs.
 *
 * Every method throws on failure. Callers (application controllers) turn the
 * failure into user-visible state; nothing is queued or retried here.
 */
public interface OpenCodeInteractionGateway {
    /**
     * Lists the questions the server is currently waiting on (`GET /question`).
     *
     * [directory] scopes the request to the active project root so a question
     * from another workspace on the same server cannot leak in (ADR-0002 §3.3).
     */
    public suspend fun pendingQuestions(directory: String? = null): List<PendingQuestion>

    /**
     * Answers a pending question (`POST /question/{requestID}/reply`).
     *
     * [answers] is one list of selected labels per asked question, matching the
     * server contract `{ answers: [[label, ...]] }`.
     */
    public suspend fun answerQuestion(
        requestId: String,
        answers: List<List<String>>,
        directory: String? = null,
    )

    /** Rejects a pending question (`POST /question/{requestID}/reject`). */
    public suspend fun rejectQuestion(requestId: String, directory: String? = null)

    /**
     * Aborts the active turn of [sessionId] (`POST /session/{sessionID}/abort`).
     *
     * Abort is an explicit user action and is never automatic (V1-08). It is
     * sent exactly once; the client does not patch any local turn state and
     * waits for the next authoritative server snapshot.
     *
     * [directory] scopes the request to the active project root so the abort
     * cannot hit another workspace's turn on the same server (ADR-0002 §3.3);
     * the pinned spec exposes it as an optional query parameter.
     */
    public suspend fun abortTurn(sessionId: String, directory: String? = null)

    /**
     * Reads what the server exposes: providers/models (`GET /provider`) and
     * agents (`GET /agent`). Never falls back to a built-in catalog.
     */
    public suspend fun serverCatalog(directory: String? = null): ServerCatalog
}

/**
 * Thrown when an interaction call is made while no verified connection is
 * active. The generated client's credential permit is only released after the
 * T1 identity check, so there is nothing to send without a connection.
 */
public class InteractionNotConnectedException(
    message: String = "No active OpenCode Server connection",
) : IllegalStateException(message)
