package org.opencodemobile.android.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import org.opencodemobile.android.MainActivity
import org.opencodemobile.android.R
import org.opencodemobile.shared.domain.notification.AppNotification
import org.opencodemobile.shared.domain.notification.LocalNotificationSink
import org.opencodemobile.shared.domain.notification.NotificationKind

/**
 * Android [LocalNotificationSink] (V1-13).
 *
 * It renders the read-only [AppNotification] plan on the Android shade. There is
 * no code path here that can add an "Approve" action: the plan's actions are the
 * shared [org.opencodemobile.shared.domain.notification.NotificationAction] enum,
 * which has no approving member, and the only button it renders is the safe
 * "Deny" carried by a permission plan.
 *
 * Notifications are marked [Notification.VISIBILITY_PRIVATE] and never carry the
 * command, arguments or diff (T13): the exact content stays on the authenticated
 * in-app confirmation screen.
 *
 * Posting is best-effort. A notification is a signal, not state: if the OS
 * permission is not granted the call is a no-op and the app loses nothing (the
 * next server read rebuilds the state).
 */
public class AndroidLocalNotificationSink(
    private val context: Context,
) : LocalNotificationSink {

    private val manager: NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    override suspend fun post(notification: AppNotification) {
        val builder = Notification.Builder(context, AndroidNotificationContent.channelId(notification.kind))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(notification.title)
            .setContentText(notification.body)
            .setStyle(Notification.BigTextStyle().bigText(notification.body))
            .setContentIntent(openAppPendingIntent(notification.tapRoute, notification.id.hashCode()))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)

        AndroidNotificationContent.actionButtons(notification).forEach { action ->
            val requestCode = notification.id.hashCode() xor action.hashCode()
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_notification),
                    AndroidNotificationContent.actionLabel(action),
                    openAppPendingIntent(notification.tapRoute, requestCode),
                ).build(),
            )
        }

        manager.notify(notification.id, NOTIFICATION_ID, builder.build())
    }

    override suspend fun cancel(id: String) {
        manager.cancel(id, NOTIFICATION_ID)
    }

    /**
     * Cancels every notification this app posted, delivered or pending.
     *
     * "Tout effacer" (ADR 0009 §2.1.4) uses this so erased session/permission
     * content does not linger in the shade or on the lock screen. Clearing the
     * whole app surface also covers notifications posted before a restart, which
     * the in-memory coordinator no longer tracks.
     */
    override suspend fun cancelAll() {
        manager.cancelAll()
    }

    /**
     * The PendingIntent the tap and every action open.
     *
     * It carries only the plan's in-app route (never a decision) and targets
     * [MainActivity], so a notification always opens the app in the foreground;
     * the deny control is confirmed there, not executed off-screen.
     */
    private fun openAppPendingIntent(route: String, requestCode: Int): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(route))
            .setClass(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    public companion object {
        /** One stable notification slot per logical id, addressed by [AppNotification.id] tag. */
        public const val NOTIFICATION_ID: Int = 0

        /**
         * Creates the three notification channels. Safe to call on every app
         * start: creating an existing channel with the same id is a no-op, so the
         * user's per-channel choices survive.
         */
        public fun createChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            for (kind in NotificationKind.entries) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        AndroidNotificationContent.channelId(kind),
                        AndroidNotificationContent.channelName(kind),
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ),
                )
            }
        }
    }
}
