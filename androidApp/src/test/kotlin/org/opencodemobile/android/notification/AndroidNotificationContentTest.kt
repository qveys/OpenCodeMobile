package org.opencodemobile.android.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.opencodemobile.shared.domain.interaction.PendingQuestion
import org.opencodemobile.shared.domain.interaction.QuestionItem
import org.opencodemobile.shared.domain.notification.AppNotification
import org.opencodemobile.shared.domain.notification.NotificationAction
import org.opencodemobile.shared.domain.notification.NotificationKind
import org.opencodemobile.shared.domain.notification.NotificationPolicy
import org.opencodemobile.shared.domain.permission.PermissionCapabilities
import org.opencodemobile.shared.domain.permission.PermissionRequest

/**
 * Android-side V1-13 / OP4 proof, run by `testDebugUnitTest` on the JVM.
 *
 * It exercises the pure projection the Android sink uses to build the shade
 * notification, so the "no Approve action" rule is asserted on the Android build
 * itself — without an emulator.
 */
class AndroidNotificationContentTest {

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

    private val permissionsNotification: AppNotification = NotificationPolicy.permissionFor(permission)

    private val allNotifications: List<AppNotification> = listOf(
        permissionsNotification,
        NotificationPolicy.questionFor(question),
        requireNotNull(NotificationPolicy.sessionCompletedFor("ses_1", "Title", "busy", "idle")),
        requireNotNull(NotificationPolicy.sessionErrorFor("ses_1", "Title", "retry")),
    )

    @Test
    fun noAndroidNotificationBuildsAnApproveAction() {
        for (notification in allNotifications) {
            for (button in AndroidNotificationContent.actionButtons(notification)) {
                assertFalse(
                    button.name.contains("approve", ignoreCase = true),
                    "Android must never build an approving action (${notification.kind})",
                )
            }
        }
    }

    @Test
    fun onlyThePermissionNotificationBuildsTheSafeDenyButton() {
        assertEquals(
            listOf(NotificationAction.Deny),
            AndroidNotificationContent.actionButtons(permissionsNotification),
        )
        for (notification in allNotifications.filter { it.kind != NotificationKind.PermissionPending }) {
            assertTrue(
                AndroidNotificationContent.actionButtons(notification).isEmpty(),
                "${notification.kind} must not build a decision button",
            )
        }
    }

    @Test
    fun everyKindMapsToAStableChannel() {
        assertEquals(
            setOf(
                "opencodemobile.permissions",
                "opencodemobile.questions",
                "opencodemobile.sessions",
            ),
            NotificationKind.entries.map(AndroidNotificationContent::channelId).toSet(),
        )
        assertEquals(
            "opencodemobile.permissions",
            AndroidNotificationContent.channelId(NotificationKind.PermissionPending),
        )
        assertEquals("Deny", AndroidNotificationContent.actionLabel(NotificationAction.Deny))
    }
}
