package org.opencodemobile.shared.domain.interaction

/**
 * Pending agent-question domain model (V1-07; `docs/ARCHITECTURE.md` §3.3).
 *
 * A question is **server state**, not client state: it is listed by
 * `GET /api/form` and it disappears only when the server says so. The client
 * therefore never persists or dismisses a question locally — a process kill
 * loses nothing because the next `GET /api/form` (on start or on reconnect)
 * returns the same pending request until it has been answered or rejected.
 *
 * The types carry no JSON, Ktor, or platform dependency (Rule R3).
 */

/** One selectable choice of a [FormField]. */
public data class FormOption(
    /** The value the answer must carry for this choice. */
    public val value: String,
    /** The label shown to the user. */
    public val label: String,
    /** Optional human-readable explanation, when the server supplies one. */
    public val description: String? = null,
)

/** The input kind of a [FormField] (the server's form field `type`). */
public enum class FormFieldType {
    /** Free text; when [FormField.options] is non-empty, a single choice. */
    Text,
    Number,
    Integer,
    Toggle,
    /** A set of choices from [FormField.options]. */
    Multiselect,
    /** Answered outside the app at [FormField.url]; carries no value. */
    External,
}

/** One input of a [PendingQuestion]. */
public data class FormField(
    /** The answer key. */
    public val key: String,
    public val type: FormFieldType,
    /** The prompt shown to the user, when the server supplies one. */
    public val title: String? = null,
    public val description: String? = null,
    public val required: Boolean = false,
    /** The choices for [FormFieldType.Text] / [FormFieldType.Multiselect]; empty means free-form only. */
    public val options: List<FormOption> = emptyList(),
    /** Whether the user may type a free-form answer in addition to [options]. */
    public val custom: Boolean = false,
    /** The out-of-band URL of a [FormFieldType.External] field. */
    public val url: String? = null,
)

/** One answered value; the variant follows the field's [FormFieldType]. */
public sealed interface FormValue {
    public data class Text(public val value: String) : FormValue
    public data class Number(public val value: Double) : FormValue
    public data class Toggle(public val value: Boolean) : FormValue
    public data class Choices(public val values: List<String>) : FormValue
}

/** The reply to a [PendingQuestion]: [FormField.key] to its value. */
public typealias FormAnswer = Map<String, FormValue>

/**
 * A question the server is waiting on before the current turn can continue,
 * modelled on the server's form (`Form.Info`): a [title] and one or more
 * [fields] to fill in.
 *
 * While [PendingQuestion] entries are pending the turn is blocked: the client
 * must not send a new prompt and must not offer a "dismiss" affordance. The
 * only valid actions are [OpenCodeInteractionGateway.answerQuestion] and
 * [OpenCodeInteractionGateway.rejectQuestion].
 */
public data class PendingQuestion(
    /** Server-issued form id (used to answer or reject). */
    public val id: String,
    /** The session whose turn is blocked on this question. */
    public val sessionId: String,
    public val title: String,
    public val fields: List<FormField> = emptyList(),
)
