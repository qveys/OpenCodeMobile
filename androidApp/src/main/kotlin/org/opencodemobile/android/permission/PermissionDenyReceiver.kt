package org.opencodemobile.android.permission

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import org.opencodemobile.features.permissions.PermissionsPresenter

/**
 * Routes the notification "Deny" action to `PermissionsPresenter.deny` (OPE-173).
 *
 * It is `android:exported="false"`: only the notification's own [android.app.PendingIntent]
 * can trigger it, so no external app can decide anything. Deny is the safe,
 * reversible direction (it grants no capability and needs no foreground/biometric
 * gate); an approve action does not exist on the notification at all.
 */
public class PermissionDenyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PERMISSION_DENY) return
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                GlobalContext.getOrNull()
                    ?.getOrNull<PermissionsPresenter>()
                    ?.deny(requestId)
            } finally {
                pendingResult.finish()
            }
        }
    }

    public companion object {
        public const val ACTION_PERMISSION_DENY: String =
            "org.opencodemobile.android.permission.DENY"

        public const val EXTRA_REQUEST_ID: String = "requestId"
    }
}
