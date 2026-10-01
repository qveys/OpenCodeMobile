package org.opencodemobile.shared.domain.interaction

/**
 * Pending agent-question domain model (V1-07; `docs/ARCHITECTURE.md` §3.3).
 *
 * A question is **server state**, not client state: it is listed by
 * `GET /question` and it disappears only when the server says so. The client
 * therefore never persists or dismisses a question locally — a process kill
 * loses nothing because the next `GET /question` (on start or on reconnect)
 * returns the same pending request until it has been answered or rejected.
 *
 * The types carry no JSON, Ktor, or platform dependency (Rule R3).
 */

/** One selectable answer offered by the server for a [QuestionItem]. */
public data class QuestionOption(
    /** The label the reply must echo back in `answers`. */
    public val label: String,
    /** Optional human-readable explanation, when the server supplies one. */
    public val description: String? = null,
)

/** One question inside a [PendingQuestion] request. */
public data class QuestionItem(
    /** The question text shown to the user. */
    public val question: String,
    /** A short header/title for the question. */
    public val header: String,
    /** The options the server offers; empty means free-form only. */
    public val options: List<QuestionOption> = emptyList(),
    /** Whether the user may select several options at once. */
    public val multiple: Boolean = false,
    /** Whether the user may type a free-form answer in addition to the options. */
    public val custom: Boolean = false,
)

/**
 * A question the server is waiting on before the current turn can continue.
 *
 * While [PendingQuestion] entries are pending the turn is blocked: the client
 * must not send a new prompt and must not offer a "dismiss" affordance. The
 * only valid actions are [OpenCodeInteractionGateway.answerQuestion] and
 * [OpenCodeInteractionGateway.rejectQuestion].
 */
public data class PendingQuestion(
    /** Server-issued request id (used to answer or reject). */
    public val id: String,
    /** The session whose turn is blocked on this question. */
    public val sessionId: String,
    /** One or more questions the server asks at once. */
    public val questions: List<QuestionItem> = emptyList(),
)
