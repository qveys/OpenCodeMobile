package org.opencodemobile.android.notification

import org.opencodemobile.shared.domain.notification.AppNotification
import org.opencodemobile.shared.domain.notification.NotificationAction
import org.opencodemobile.shared.domain.notification.NotificationKind
import org.opencodemobile.shared.domain.notification.NotificationPolicy

/**
 * The Android projection of an [AppNotification] (V1-13).
 *
 * It is deliberately pure Kotlin — no `android.*` import — so the Android/JVM
 * unit test (`testDebugUnitTest`) can prove the OP4 property on the Android build
 * without an emulator: the notification's channel and action buttons are derived
 * from [NotificationAction], which has **no approving member**.
 *
 * Only the decision-bearing actions become buttons. "Open" is not a button: the
 * notification's own tap already brings the app forward, so adding an "Open"
 * button would duplicate it. Tapping the "Deny" button does not decide anything
 * off-screen either: it opens the app at the request so the safe decision is
 * confirmed in the foreground (V1-13 criterion 3: no decision off-foreground).
 */
public object AndroidNotificationContent {

    /** One Android notification channel per [NotificationKind]. */
    public fun channelId(kind: NotificationKind): String = when (kind) {
        NotificationKind.PermissionPending -> "opencodemobile.permissions"
        NotificationKind.QuestionPending -> "opencodemobile.questions"
        NotificationKind.SessionCompleted -> "opencodemobile.sessions"
        NotificationKind.SessionError -> "opencodemobile.sessions"
    }

    /** The user-visible channel name (localized by the platform settings UI). */
    public fun channelName(kind: NotificationKind): String = when (kind) {
        NotificationKind.PermissionPending -> "Permissions"
        NotificationKind.QuestionPending -> "Questions"
        NotificationKind.SessionCompleted -> "Sessions"
        NotificationKind.SessionError -> "Sessions"
    }

    /**
     * The action buttons to render for [notification]: every plan action except
     * the implicit "Open" tap. On Android that is at most "Deny", and never an
     * approval.
     */
    public fun actionButtons(notification: AppNotification): List<NotificationAction> =
        notification.actions.filter { it != NotificationAction.Open }

    /** The label a button shows for [action], from the shared policy. */
    public fun actionLabel(action: NotificationAction): String = NotificationPolicy.actionLabel(action)
}
