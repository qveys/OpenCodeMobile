package org.opencodemobile.shared.application.notification

import org.opencodemobile.shared.domain.permission.PermissionNotifier
import org.opencodemobile.shared.domain.permission.PermissionRequest

/**
 * Adapts the V1-06 [PermissionNotifier] port onto the V1-13 local-notification
 * coordinator.
 *
 * It is the seam that keeps the two lots independent: the permission state
 * machine talks to [PermissionNotifier] (its own port, already delivered) while
 * the concrete signal is rendered by [LocalNotificationCoordinator], which can
 * only build read-only plans. Wiring this in place of
 * `NoOpPermissionNotifier` turns the V1-06 hook into a real local notification.
 */
public class NotificationPermissionNotifier(
    private val coordinator: LocalNotificationCoordinator,
) : PermissionNotifier {
    override suspend fun onPendingChanged(pending: List<PermissionRequest>): Unit =
        coordinator.onPendingPermissions(pending)
}
