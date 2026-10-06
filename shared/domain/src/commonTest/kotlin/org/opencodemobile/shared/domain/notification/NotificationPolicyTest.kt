package org.opencodemobile.shared.domain.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.opencodemobile.shared.domain.interaction.PendingQuestion
import org.opencodemobile.shared.domain.interaction.QuestionItem
import org.opencodemobile.shared.domain.permission.PermissionCapabilities
import org.opencodemobile.shared.domain.permission.PermissionPolicy
import org.opencodemobile.shared.domain.permission.PermissionRequest

/**
 * V1-13 / OP4 unit tests for the local-notification surface, run on both the
 * Android (JVM) and iOS (Kotlin/Native) test targets.
 *
 * The central property is negative and structural: no notification, for any of
 * the four kinds, can carry an approving action.
 */
class NotificationPolicyTest {

    private val bashRequest = PermissionRequest(
        id = "per_mock_0001",
        sessionId = "ses_mock_0001",
        tool = "bash",
        patterns = listOf("rm -rf build"),
        rawArguments = """{"command":"rm -rf build"}""",
        rememberScopes = listOf("bash:rm"),
        capabilities = PermissionCapabilities.fromServer(listOf("bash:rm")),
    )

    private val question = PendingQuestion(
        id = "q_mock_0001",
        sessionId = "ses_mock_0001",
        questions = listOf(QuestionItem(question = "Which branch?", header = "Branch")),
    )

    @Test
    fun theActionVocabularyHasNoApprovingMember() {
        assertEquals(
            listOf(NotificationAction.Open, NotificationAction.Deny),
            NotificationAction.entries.toList(),
        )
        for (action in NotificationAction.entries) {
            assertFalse(
                action.name.contains("approve", ignoreCase = true),
                "the notification action vocabulary must never contain Approve (${action.name})",
            )
        }
        assertFalse(NotificationPolicy.NOTIFICATION_CAN_APPROVE)
    }

    @Test
    fun everyNotificationKindNeverCarriesAnApprovingAction() {
        val notifications = listOf(
            NotificationPolicy.permissionFor(bashRequest),
            NotificationPolicy.questionFor(question),
            requireNotNull(NotificationPolicy.sessionCompletedFor("ses_1", "Title", "busy", "idle")),
            requireNotNull(NotificationPolicy.sessionErrorFor("ses_1", "Title", "retry")),
        )
        assertEquals(
            NotificationKind.entries.toSet(),
            notifications.map { it.kind }.toSet(),
            "the four V1-13 triggers must each build a plan",
        )
        for (notification in notifications) {
            for (action in notification.actions) {
                assertFalse(
                    action.name.contains("approve", ignoreCase = true),
                    "${notification.kind} must not carry an approving action",
                )
            }
        }
    }

    @Test
    fun onlyThePermissionNotificationOffersTheSafeDeny() {
        val permission = NotificationPolicy.permissionFor(bashRequest)
        assertEquals(listOf(NotificationAction.Open, NotificationAction.Deny), permission.actions)

        val others = listOf(
            NotificationPolicy.questionFor(question),
            requireNotNull(NotificationPolicy.sessionCompletedFor("ses_1", null, "busy", "idle")),
            requireNotNull(NotificationPolicy.sessionErrorFor("ses_1", null, "retry")),
        )
        for (notification in others) {
            assertEquals(
                listOf(NotificationAction.Open),
                notification.actions,
                "${notification.kind} may only open the app, never decide",
            )
        }
    }

    @Test
    fun aOnceOnlyPermissionOffersNoDeny() {
        val onceOnly = bashRequest.copy(capabilities = PermissionCapabilities.OnceOnly)
        assertEquals(
            listOf(NotificationAction.Open),
            NotificationPolicy.permissionFor(onceOnly).actions,
        )
    }

    @Test
    fun permissionNotificationDeepLinksToTheConfirmationAndLeaksNothing() {
        val notification = NotificationPolicy.permissionFor(bashRequest)
        assertEquals(
            PermissionPolicy.CONFIRMATION_ROUTE_PREFIX + bashRequest.id,
            notification.tapRoute,
        )
        assertFalse(notification.tapRoute.contains("once"))
        assertFalse(notification.tapRoute.contains("always"))
        assertFalse(notification.tapRoute.contains("reject"))
        assertFalse(notification.body.contains("rm -rf"))
    }

    @Test
    fun questionNotificationOnlyOpensTheQuestionScreen() {
        val notification = NotificationPolicy.questionFor(question)
        assertEquals(NotificationPolicy.QUESTION_ROUTE_PREFIX + question.id, notification.tapRoute)
        assertEquals(listOf(NotificationAction.Open), notification.actions)
        assertTrue(notification.title.contains("Branch"), "the header is surfaced, sanitized")
    }

    @Test
    fun sessionCompletionIsOnlyAFreshBusyToIdleEdge() {
        val completed = NotificationPolicy.sessionCompletedFor("ses_1", "My session", "busy", "idle")
        assertEquals(NotificationKind.SessionCompleted, completed?.kind)
        assertEquals(
            NotificationPolicy.SESSION_ROUTE_PREFIX + "ses_1",
            completed?.tapRoute,
        )
        assertNull(NotificationPolicy.sessionCompletedFor("ses_1", null, null, "idle"))
        assertNull(NotificationPolicy.sessionCompletedFor("ses_1", null, "busy", "busy"))
        assertNull(NotificationPolicy.sessionCompletedFor("ses_1", null, "idle", "idle"))
    }

    @Test
    fun sessionErrorIsOnlyReportedForTheServersRetryState() {
        val error = NotificationPolicy.sessionErrorFor("ses_1", "My session", "retry")
        assertEquals(NotificationKind.SessionError, error?.kind)
        assertNull(NotificationPolicy.sessionErrorFor("ses_1", null, "idle"))
        assertNull(NotificationPolicy.sessionErrorFor("ses_1", null, "busy"))
    }

    @Test
    fun notificationIdsAreStableAndKindScoped() {
        assertEquals(
            NotificationPolicy.notificationId(NotificationKind.PermissionPending, "per_1"),
            NotificationPolicy.notificationId(NotificationKind.PermissionPending, "per_1"),
        )
        assertFalse(
            NotificationPolicy.notificationId(NotificationKind.PermissionPending, "per_1") ==
                NotificationPolicy.notificationId(NotificationKind.QuestionPending, "per_1"),
        )
        assertFalse(
            NotificationPolicy.notificationId(NotificationKind.SessionCompleted, "ses_1") ==
                NotificationPolicy.notificationId(NotificationKind.SessionError, "ses_1"),
        )
    }
}
