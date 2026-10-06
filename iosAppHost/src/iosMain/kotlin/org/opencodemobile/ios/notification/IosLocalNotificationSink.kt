@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.opencodemobile.ios.notification

import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationAction
import platform.UserNotifications.UNNotificationActionOptionForeground
import platform.UserNotifications.UNNotificationCategory
import platform.UserNotifications.UNNotificationCategoryOptionNone
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNUserNotificationCenter
import org.opencodemobile.shared.domain.notification.AppNotification
import org.opencodemobile.shared.domain.notification.LocalNotificationSink

/**
 * iOS [LocalNotificationSink] (V1-13).
 *
 * It renders the read-only [AppNotification] plan with the UserNotifications
 * framework. There is no code path that can add an "Approve" action: the plan's
 * actions are the shared
 * [org.opencodemobile.shared.domain.notification.NotificationAction] enum, which
 * has no approving member; the only button registered is the safe "Deny" carried
 * by a permission plan, and it is declared foreground so it opens the app rather
 * than deciding off-screen (V1-13 criterion 3).
 *
 * Posting is best-effort: a notification is a signal, not state. If the user has
 * not granted notification authorization the request is dropped and the app
 * loses nothing (the next server read rebuilds the state).
 *
 * The category registration uses [IosNotificationContent], whose identifier set
 * is asserted by `IosNotificationContentTest` on the simulator.
 */
public class IosLocalNotificationSink : LocalNotificationSink {

    private val center: UNUserNotificationCenter = UNUserNotificationCenter.currentNotificationCenter()

    init {
        registerPermissionCategory()
        requestAuthorization()
    }

    override suspend fun post(notification: AppNotification) {
        val content = UNMutableNotificationContent()
        content.setTitle(notification.title)
        content.setBody(notification.body)
        content.setUserInfo(IosNotificationContent.userInfo(notification))
        IosNotificationContent.categoryIdentifier(notification)?.let { category ->
            content.setCategoryIdentifier(category)
        }

        // A null trigger delivers the notification immediately.
        val request = UNNotificationRequest.requestWithIdentifier(
            identifier = notification.id,
            content = content,
            trigger = null,
        )
        center.addNotificationRequest(request, null)
    }

    override suspend fun cancel(id: String) {
        center.removePendingNotificationRequestsWithIdentifiers(listOf(id))
        center.removeDeliveredNotificationsWithIdentifiers(listOf(id))
    }

    /**
     * Registers the single permission category. It carries the safe "Deny"
     * action only; there is deliberately no approving action to register.
     */
    private fun registerPermissionCategory() {
        val deny = UNNotificationAction.actionWithIdentifier(
            identifier = IosNotificationContent.DENY_ACTION_IDENTIFIER,
            title = IosNotificationContent.actionLabel(
                org.opencodemobile.shared.domain.notification.NotificationAction.Deny,
            ),
            options = UNNotificationActionOptionForeground,
        )
        val category = UNNotificationCategory.categoryWithIdentifier(
            identifier = IosNotificationContent.PERMISSION_CATEGORY,
            actions = listOf(deny),
            intentIdentifiers = emptyList<Any>(),
            options = UNNotificationCategoryOptionNone,
        )
        center.setNotificationCategories(setOf(category))
    }

    /** Asks for alert/sound/badge authorization; a denial is not an error. */
    private fun requestAuthorization() {
        center.requestAuthorizationWithOptions(
            options = UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge,
        ) { _, _ -> }
    }
}
