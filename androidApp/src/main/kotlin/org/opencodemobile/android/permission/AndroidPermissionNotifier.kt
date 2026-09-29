package org.opencodemobile.android.permission

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.opencodemobile.android.MainActivity
import org.opencodemobile.shared.domain.permission.PermissionNotificationAction
import org.opencodemobile.shared.domain.permission.PermissionNotifier
import org.opencodemobile.shared.domain.permission.PermissionPolicy
import org.opencodemobile.shared.domain.permission.PermissionRequest

/**
 * The Android [PermissionNotifier] (V1-06, OP4).
 *
 * It posts exactly the plan built by [PermissionPolicy.notificationFor]:
 *
 * - the title/body come straight from the policy (tool only, never the command or
 *   the target paths), so the locked screen never leaks the exact request (T13);
 * - the tap opens `opencodemobile://permission/confirmation/{id}`, which the app
 *   resolves to the foreground confirmation screen and **never** reads as a decision;
 * - the only action is the safe, reversible "Deny", which goes through a
 *   non-exported broadcast receiver to `PermissionsPresenter.deny`.
 *
 * There is deliberately **no approve action** and no approving deep link. Approval
 * is only reachable in-app, on the confirmation screen, after the biometric gate.
 */
public class AndroidPermissionNotifier(
    private val context: Context,
) : PermissionNotifier {

    private val postedIds: MutableSet<Int> = mutableSetOf()

    override suspend fun onPendingChanged(pending: List<PermissionRequest>) {
        ensureChannel()
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) {
            postedIds.clear()
            return
        }

        val liveIds = pending.map { notificationId(it.id) }.toSet()
        for (request in pending) {
            val plan = PermissionPolicy.notificationFor(request)
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(plan.title)
                .setContentText(plan.body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(plan.body))
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                // Not on the lockscreen: the exact request is shown in-app only.
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .setOnlyAlertOnce(false)
                .setContentIntent(openConfirmation(plan.requestId, plan.tapRoute))
            for (action in plan.actions) {
                when (action) {
                    PermissionNotificationAction.OpenConfirmation -> Unit // handled by the tap
                    PermissionNotificationAction.Deny ->
                        builder.addAction(0, denyLabel(), deny(plan.requestId))
                }
            }
            runCatching { manager.notify(notificationId(plan.requestId), builder.build()) }
        }

        // A request that is no longer pending must not keep a stale notification.
        for (staleId in postedIds - liveIds) {
            runCatching { manager.cancel(staleId) }
        }
        postedIds.clear()
        postedIds += liveIds
    }

    private fun openConfirmation(requestId: String, route: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(route)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            context,
            notificationId(requestId) + OPEN_OFFSET,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun deny(requestId: String): PendingIntent {
        val intent = Intent(context, PermissionDenyReceiver::class.java).apply {
            action = PermissionDenyReceiver.ACTION_PERMISSION_DENY
            putExtra(PermissionDenyReceiver.EXTRA_REQUEST_ID, requestId)
        }
        return PendingIntent.getBroadcast(
            context,
            notificationId(requestId) + DENY_OFFSET,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Permission requests",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Signals a pending permission request. Open the app to decide."
            },
        )
    }

    private fun denyLabel(): CharSequence = "Deny"

    private fun notificationId(requestId: String): Int = requestId.hashCode()

    private companion object {
        private const val CHANNEL_ID: String = "permission_requests"
        private const val OPEN_OFFSET: Int = 1
        private const val DENY_OFFSET: Int = 2
    }
}
