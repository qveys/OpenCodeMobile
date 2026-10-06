package org.opencodemobile.ios.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.opencodemobile.shared.domain.interaction.PendingQuestion
import org.opencodemobile.shared.domain.interaction.QuestionItem
import org.opencodemobile.shared.domain.notification.NotificationKind
import org.opencodemobile.shared.domain.notification.NotificationPolicy
import org.opencodemobile.shared.domain.permission.PermissionCapabilities
import org.opencodemobile.shared.domain.permission.PermissionRequest

/**
 * iOS-side V1-13 / OP4 proof, run by `iosSimulatorArm64Test` on the simulator.
 *
 * It exercises the pure projection the iOS sink registers with
 * `UNUserNotificationCenter`, so the "no Approve action" rule is asserted on the
 * iOS build as well as the Android one.
 */
class IosNotificationContentTest {

    private val permission = PermissionRequest(
        id = "per_mock_0001",
        sessionId = null,
        tool = "bash",
        patterns = listOf("rm -rf build"),
        rawArguments = """{"command":"rm -rf build"}""",
        rememberScopes = emptyList(),
        capabilities = PermissionCapabilities.fromServer(emptyList()),
    )

    private val question = PendingQuestion(
        id = "q_mock_0001",
        sessionId = "ses_1",
        questions = listOf(QuestionItem(question = "Which branch?", header = "Branch")),
    )

    private val permissionsNotification = NotificationPolicy.permissionFor(permission)

    private val allNotifications = listOf(
        permissionsNotification,
        NotificationPolicy.questionFor(question),
        requireNotNull(NotificationPolicy.sessionCompletedFor("ses_1", "Title", "busy", "idle")),
        requireNotNull(NotificationPolicy.sessionErrorFor("ses_1", "Title", "retry")),
    )

    @Test
    fun noIosNotificationRegistersAnApproveAction() {
        for (notification in allNotifications) {
            for (identifier in IosNotificationContent.actionIdentifiers(notification)) {
                assertFalse(
                    identifier.contains("approve", ignoreCase = true),
                    "iOS must never register an approving action (${notification.kind})",
                )
            }
        }
    }

    @Test
    fun onlyThePermissionNotificationRegistersTheSafeDeny() {
        assertEquals(
            listOf(IosNotificationContent.DENY_ACTION_IDENTIFIER),
            IosNotificationContent.actionIdentifiers(permissionsNotification),
        )
        for (notification in allNotifications.filter { it.kind != NotificationKind.PermissionPending }) {
            assertTrue(
                IosNotificationContent.actionIdentifiers(notification).isEmpty(),
                "${notification.kind} must not register an action",
            )
        }
    }

    @Test
    fun onlyThePermissionNotificationCarriesACategory() {
        assertEquals(
            IosNotificationContent.PERMISSION_CATEGORY,
            IosNotificationContent.categoryIdentifier(permissionsNotification),
        )
        for (notification in allNotifications.filter { it.kind != NotificationKind.PermissionPending }) {
            assertNull(IosNotificationContent.categoryIdentifier(notification))
        }
    }

    @Test
    fun theUserInfoCarriesOnlyTheRouteAndKind() {
        val userInfo = IosNotificationContent.userInfo(permissionsNotification)
        assertEquals(permissionsNotification.tapRoute, userInfo[IosNotificationContent.ROUTE_USER_INFO_KEY])
        assertEquals(
            NotificationKind.PermissionPending.name,
            userInfo[IosNotificationContent.KIND_USER_INFO_KEY],
        )
        assertFalse(userInfo.values.any { it.toString().contains("once") })
        assertFalse(userInfo.values.any { it.toString().contains("reject") })
    }
}
