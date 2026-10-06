package org.opencodemobile.shared.domain.notification

import org.opencodemobile.shared.domain.interaction.PendingQuestion
import org.opencodemobile.shared.domain.permission.PermissionDisplay
import org.opencodemobile.shared.domain.permission.PermissionNotificationAction
import org.opencodemobile.shared.domain.permission.PermissionPolicy
import org.opencodemobile.shared.domain.permission.PermissionRequest

/**
 * The only actions a local notification may ever carry (V1-13, OP4).
 *
 * There is deliberately **no `Approve` member**: the type system, not a runtime
 * check, is what forbids a notification from authorizing anything. Approving a
 * permission stays on the in-app foreground confirmation screen with its
 * biometric gate (`docs/ARCHITECTURE.md` §"Permission approval confirmation").
 *
 * [Deny] is kept because denying is the safe, reversible direction: it can at
 * most make the agent ask again, and it grants no capability.
 */
public enum class NotificationAction {
    /** Brings the app forward; carries no decision. */
    Open,

    /** The safe direction: it can only make the agent ask again (permissions). */
    Deny,
}

/** The four V1-13 triggers (`Cahier des charges` §9.4). */
public enum class NotificationKind {
    /** A tool-call permission request is pending. */
    PermissionPending,

    /** The agent is waiting on a question before the turn can continue. */
    QuestionPending,

    /** The active turn finished. */
    SessionCompleted,

    /** The server reported an error / retry state for the session. */
    SessionError,
}

/**
 * A local, read-only notification plan.
 *
 * The plan is built by the pure [NotificationPolicy] and consumed by a platform
 * `LocalNotificationSink`. [tapRoute] is an in-app deep link that carries only an
 * identifier: it never embeds a decision, so tapping a notification cannot
 * approve or deny anything by itself. The body deliberately never contains the
 * exact command, arguments or file diffs — a notification is visible on a locked
 * screen, and the exact content is shown only on the authenticated in-app
 * surface (T13).
 */
public data class AppNotification(
    /** Stable id used to post, replace and cancel this notification. */
    public val id: String,
    public val kind: NotificationKind,
    public val title: String,
    public val body: String,
    public val tapRoute: String,
    public val actions: List<NotificationAction>,
) {
    init {
        require(id.isNotBlank()) { "notification id must not be blank" }
        require(tapRoute.isNotBlank()) { "notification tap route must not be blank" }
        require(NotificationAction.Deny !in actions || kind == NotificationKind.PermissionPending) {
            "only a pending-permission notification may offer the safe Deny action"
        }
    }
}

/**
 * Pure policy for the local-notification surface (V1-13) and the OP4 rule: a
 * notification surfaces state, it never authorizes.
 *
 * The four builders below cover the V1-13 triggers. Every one of them returns a
 * plan whose actions come from [NotificationAction], which has no approving
 * member. The permission builder delegates to [PermissionPolicy.notificationFor]
 * so the V1-06 security rule and the V1-13 surface cannot drift apart.
 */
public object NotificationPolicy {

    /**
     * Structural guarantee of OP4, stated as one constant so the security review
     * has a single place to point at: a notification never approves.
     */
    public const val NOTIFICATION_CAN_APPROVE: Boolean = false

    /** The confirmation screen route, shared with V1-06 so a tap cannot drift. */
    public const val CONFIRMATION_ROUTE_PREFIX: String = PermissionPolicy.CONFIRMATION_ROUTE_PREFIX

    /** Route prefix for a pending agent question. */
    public const val QUESTION_ROUTE_PREFIX: String = "opencodemobile://question/pending/"

    /** Route prefix for a finished or failed session. */
    public const val SESSION_ROUTE_PREFIX: String = "opencodemobile://session/"

    /** The label a platform shows for [action]. */
    public fun actionLabel(action: NotificationAction): String = when (action) {
        NotificationAction.Open -> "Open"
        NotificationAction.Deny -> "Deny"
    }

    /**
     * The local notification for a pending permission request: informational
     * only, deep-linking to the foreground confirmation screen, with at most the
     * safe "Deny" action.
     */
    public fun permissionFor(request: PermissionRequest): AppNotification {
        val permission = PermissionPolicy.notificationFor(request)
        return AppNotification(
            id = notificationId(NotificationKind.PermissionPending, request.id),
            kind = NotificationKind.PermissionPending,
            title = permission.title,
            body = permission.body,
            tapRoute = permission.tapRoute,
            actions = permission.actions.map { it.toNotificationAction() },
        )
    }

    /**
     * The local notification for a pending agent question. It opens the question
     * screen; it offers no answer/reject action, so a notification cannot move
     * the turn forward.
     */
    public fun questionFor(question: PendingQuestion): AppNotification {
        val header = question.questions.firstOrNull()?.header?.let(PermissionDisplay::sanitizeForDisplay)
        return AppNotification(
            id = notificationId(NotificationKind.QuestionPending, question.id),
            kind = NotificationKind.QuestionPending,
            title = if (header.isNullOrBlank()) QUESTION_TITLE else "$QUESTION_TITLE · $header",
            body = "Open the app to answer.",
            tapRoute = QUESTION_ROUTE_PREFIX + question.id,
            actions = listOf(NotificationAction.Open),
        )
    }

    /**
     * The notification for a turn that just finished, or null when the transition
     * is not a completion. A completion is `busy → idle`: only then did the agent
     * stop working on its own.
     */
    public fun sessionCompletedFor(
        sessionId: String,
        title: String?,
        previousType: String?,
        currentType: String,
    ): AppNotification? {
        if (previousType != SESSION_BUSY || currentType != SESSION_IDLE) return null
        return sessionNotification(
            id = notificationId(NotificationKind.SessionCompleted, sessionId),
            kind = NotificationKind.SessionCompleted,
            title = "Session finished",
            titleSuffix = title,
            tapRoute = SESSION_ROUTE_PREFIX + sessionId,
        )
    }

    /**
     * The notification for a session the server put into its error/retry state,
     * or null when the current status is not one. It is idempotent: the same
     * session in `retry` yields the same plan, so a caller cannot spam the shade.
     */
    public fun sessionErrorFor(
        sessionId: String,
        title: String?,
        currentType: String,
    ): AppNotification? {
        if (currentType != SESSION_RETRY) return null
        return sessionNotification(
            id = notificationId(NotificationKind.SessionError, sessionId),
            kind = NotificationKind.SessionError,
            title = "Session failed",
            titleSuffix = title,
            tapRoute = SESSION_ROUTE_PREFIX + sessionId,
        )
    }

    private fun sessionNotification(
        id: String,
        kind: NotificationKind,
        title: String,
        titleSuffix: String?,
        tapRoute: String,
    ): AppNotification {
        val suffix = titleSuffix?.let(PermissionDisplay::sanitizeForDisplay)?.takeIf { it.isNotBlank() }
        return AppNotification(
            id = id,
            kind = kind,
            title = if (suffix == null) title else "$title · $suffix",
            body = "Open the app to review the session.",
            tapRoute = tapRoute,
            actions = listOf(NotificationAction.Open),
        )
    }

    /** Stable notification id for [kind] and [entityId] (request or session id). */
    public fun notificationId(kind: NotificationKind, entityId: String): String = when (kind) {
        NotificationKind.PermissionPending -> "permission:$entityId"
        NotificationKind.QuestionPending -> "question:$entityId"
        NotificationKind.SessionCompleted -> "session-completed:$entityId"
        NotificationKind.SessionError -> "session-error:$entityId"
    }

    private fun PermissionNotificationAction.toNotificationAction(): NotificationAction = when (this) {
        PermissionNotificationAction.OpenConfirmation -> NotificationAction.Open
        PermissionNotificationAction.Deny -> NotificationAction.Deny
    }

    private const val QUESTION_TITLE: String = "Question from the agent"
    private const val SESSION_BUSY: String = "busy"
    private const val SESSION_IDLE: String = "idle"
    private const val SESSION_RETRY: String = "retry"
}
