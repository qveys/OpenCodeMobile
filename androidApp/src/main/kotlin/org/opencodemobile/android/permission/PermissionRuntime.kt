package org.opencodemobile.android.permission

import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.opencodemobile.shared.application.permission.PermissionCoordinator
import org.opencodemobile.shared.application.permission.PermissionRealtimeBridge

/**
 * The current foreground [FragmentActivity], for the lazy biometric prompt.
 *
 * `AndroidBiometricAuthenticator` needs the activity that owns the prompt at the
 * moment of the approval; `MainActivity` publishes itself here on start/stop.
 * Backed by a single reference touched only on the main thread.
 */
internal object PermissionHostActivity {
    var current: FragmentActivity? = null
}

/**
 * Starts the permission surface and its realtime bridge (OPE-173).
 *
 * `start` restores the persisted pending set (a killed app brings the banner back
 * with no implicit approval), then the bridge feeds `permission.asked` /
 * `permission.replied` into the coordinator and reconciles on every `Live`
 * transition. It sends nothing: approvals still require the foreground
 * confirmation plus the biometric gate.
 */
public class PermissionRuntime(
    private val coordinator: PermissionCoordinator?,
    private val bridge: PermissionRealtimeBridge?,
) {
    /** No-op while no connection is wired; safe to call once. */
    public fun start(scope: CoroutineScope) {
        val coordinator = coordinator ?: return
        scope.launch { coordinator.start() }
        bridge?.start(scope)
    }
}
