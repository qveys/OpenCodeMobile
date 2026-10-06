package org.opencodemobile.ios.notification

import org.opencodemobile.shared.domain.notification.AppNotification
import org.opencodemobile.shared.domain.notification.NotificationAction
import org.opencodemobile.shared.domain.notification.NotificationKind
import org.opencodemobile.shared.domain.notification.NotificationPolicy

/**
 * The iOS projection of an [AppNotification] (V1-13).
 *
 * Pure Kotlin — no `platform.*` import — so the iOS simulator test can prove the
 * OP4 property on the iOS build: the notification's category and its actions come
 * from [NotificationAction], which has **no approving member**.
 *
 * Only a pending permission gets a category, and that category carries at most
 * the "Deny" action. Every action is declared
 * `UNNotificationActionOptionForeground`, so tapping it opens the app instead of
 * deciding off-screen (V1-13 criterion 3).
 */
public object IosNotificationContent {

    /** The `UNNotificationCategory` for a pending permission request. */
    public const val PERMISSION_CATEGORY: String = "opencodemobile.permissions"

    /** Identifier of the safe "Deny" action inside [PERMISSION_CATEGORY]. */
    public const val DENY_ACTION_IDENTIFIER: String = "opencodemobile.permission.deny"

    /** Identifier of the foreground "Open" action (the implicit tap target). */
    public const val OPEN_ACTION_IDENTIFIER: String = "opencodemobile.notification.open"

    /** `userInfo` key carrying the in-app route a notification tap opens. */
    public const val ROUTE_USER_INFO_KEY: String = "opencodemobile.route"

    /** `userInfo` key carrying the [NotificationKind] name. */
    public const val KIND_USER_INFO_KEY: String = "opencodemobile.kind"

    /**
     * The category identifier to attach to [notification], or null when it needs
     * no actions. Only a pending permission request carries one.
     */
    public fun categoryIdentifier(notification: AppNotification): String? =
        if (notification.kind == NotificationKind.PermissionPending) PERMISSION_CATEGORY else null

    /**
     * The action identifiers the platform may render for [notification]: every
     * plan action except the implicit "Open" tap, mapped to its iOS identifier.
     * On iOS that is at most "Deny", and never an approval.
     */
    public fun actionIdentifiers(notification: AppNotification): List<String> =
        notification.actions
            .filter { it != NotificationAction.Open }
            .map { actionIdentifier(it) }

    /** The iOS identifier for [action], from the shared policy. */
    public fun actionIdentifier(action: NotificationAction): String = when (action) {
        NotificationAction.Open -> OPEN_ACTION_IDENTIFIER
        NotificationAction.Deny -> DENY_ACTION_IDENTIFIER
    }

    /** The label an action shows, from the shared policy. */
    public fun actionLabel(action: NotificationAction): String = NotificationPolicy.actionLabel(action)

    /** The `userInfo` payload carrying only the route and kind — never a decision. */
    public fun userInfo(notification: AppNotification): Map<Any?, Any?> = mapOf(
        ROUTE_USER_INFO_KEY to notification.tapRoute,
        KIND_USER_INFO_KEY to notification.kind.name,
    )
}
