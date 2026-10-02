package org.opencodemobile.shared.domain.interaction

/**
 * Thrown when a prompt send is attempted while the server is still waiting on a
 * pending question (V1-07).
 *
 * A question blocks the turn: until it has been answered or rejected, the
 * composer must not send a new prompt. The exception is the fail-closed backstop
 * behind the disabled composer affordance; the UI already refuses the action, so
 * a well-behaved caller never reaches it.
 */
public class TurnBlockedByPendingQuestionException(
    message: String = "A pending question blocks the turn; answer or reject it first (V1-07)",
) : IllegalStateException(message)
