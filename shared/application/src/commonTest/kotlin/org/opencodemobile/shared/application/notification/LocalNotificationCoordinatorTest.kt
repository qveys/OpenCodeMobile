package org.opencodemobile.shared.application.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.opencodemobile.shared.domain.event.SessionSnapshot
import org.opencodemobile.shared.domain.event.SessionStatus
import org.opencodemobile.shared.domain.interaction.PendingQuestion
import org.opencodemobile.shared.domain.interaction.QuestionItem
import org.opencodemobile.shared.domain.notification.AppNotification
import org.opencodemobile.shared.domain.notification.LocalNotificationSink
import org.opencodemobile.shared.domain.notification.NotificationKind
import org.opencodemobile.shared.domain.notification.NotificationPolicy
import org.opencodemobile.shared.domain.permission.PermissionCapabilities
import org.opencodemobile.shared.domain.permission.PermissionRequest

/**
 * V1-13 coordinator tests: the four triggers reach the sink, stale signals are
 * cancelled, the surface is idempotent, and a platform that refuses to post never
 * breaks the caller (a notification is a signal, not state).
 */
class LocalNotificationCoordinatorTest {

    private val permission = PermissionRequest(
        id = "per_mock_0001",
        sessionId = "ses_mock_0001",
        tool = "bash",
        patterns = listOf("rm -rf build"),
        rawArguments = """{"command":"rm -rf build"}""",
        rememberScopes = emptyList(),
        capabilities = PermissionCapabilities.fromServer(emptyList()),
    )

    private val question = PendingQuestion(
        id = "q_mock_0001",
        sessionId = "ses_mock_0001",
        questions = listOf(QuestionItem(question = "Which branch?", header = "Branch")),
    )

    @Test
    fun pendingPermissionPostsOneReadOnlyNotificationPerRequest() = runTest {
        val sink = RecordingSink()
        val coordinator = LocalNotificationCoordinator(sink)

        coordinator.onPendingPermissions(listOf(permission))

        assertEquals(listOf(NotificationKind.PermissionPending), sink.posts.map { it.kind })
        assertEquals(
            listOf(NotificationPolicy.notificationId(NotificationKind.PermissionPending, permission.id)),
            sink.posts.map { it.id },
        )
        assertFalse(sink.posts.single().actions.any { it.name.contains("approve", ignoreCase = true) })
    }

    @Test
    fun resolvingTheRequestCancelsItsNotification() = runTest {
        val sink = RecordingSink()
        val coordinator = LocalNotificationCoordinator(sink)

        coordinator.onPendingPermissions(listOf(permission))
        coordinator.onPendingPermissions(emptyList())

        assertEquals(listOf(NotificationPolicy.notificationId(NotificationKind.PermissionPending, permission.id)), sink.cancelled)
    }

    @Test
    fun reDeliveringTheSamePendingSetDoesNotRepost() = runTest {
        val sink = RecordingSink()
        val coordinator = LocalNotificationCoordinator(sink)

        coordinator.onPendingPermissions(listOf(permission))
        coordinator.onPendingPermissions(listOf(permission))

        assertEquals(1, sink.posts.size, "the coordinator is idempotent per id")
    }

    @Test
    fun pendingQuestionsAreMirroredAndStaleOnesCancelled() = runTest {
        val sink = RecordingSink()
        val coordinator = LocalNotificationCoordinator(sink)

        coordinator.onPendingQuestions(listOf(question))
        coordinator.onPendingQuestions(emptyList())

        assertEquals(listOf(NotificationKind.QuestionPending), sink.posts.map { it.kind })
        assertEquals(
            listOf(NotificationPolicy.notificationId(NotificationKind.QuestionPending, question.id)),
            sink.cancelled,
        )
    }

    @Test
    fun aBusyToIdleTransitionPostsTheCompletion() = runTest {
        val sink = RecordingSink()
        val coordinator = LocalNotificationCoordinator(sink)
        val sessions = listOf(SessionSnapshot(id = "ses_1", title = "My session", directory = null, updatedAt = null))

        coordinator.onSessionStatuses(sessions, mapOf("ses_1" to SessionStatus("busy")))
        coordinator.onSessionStatuses(sessions, mapOf("ses_1" to SessionStatus("idle")))

        assertEquals(listOf(NotificationKind.SessionCompleted), sink.posts.map { it.kind })
        assertTrue(sink.posts.single().title.contains("My session"))
    }

    @Test
    fun startingTheSessionAgainClearsItsCompletion() = runTest {
        val sink = RecordingSink()
        val coordinator = LocalNotificationCoordinator(sink)
        val sessions = listOf(SessionSnapshot("ses_1", "My session", null, null))

        coordinator.onSessionStatuses(sessions, mapOf("ses_1" to SessionStatus("busy")))
        coordinator.onSessionStatuses(sessions, mapOf("ses_1" to SessionStatus("idle")))
        coordinator.onSessionStatuses(sessions, mapOf("ses_1" to SessionStatus("busy")))

        assertTrue(
            NotificationPolicy.notificationId(NotificationKind.SessionCompleted, "ses_1") in sink.cancelled,
        )
    }

    @Test
    fun aRetryStatePostsTheErrorAndRecoveryClearsIt() = runTest {
        val sink = RecordingSink()
        val coordinator = LocalNotificationCoordinator(sink)
        val sessions = listOf(SessionSnapshot("ses_1", "My session", null, null))

        coordinator.onSessionStatuses(sessions, mapOf("ses_1" to SessionStatus("retry", message = "rate limited")))
        assertEquals(listOf(NotificationKind.SessionError), sink.posts.map { it.kind })

        // The server recovers the session to idle: the error signal is no longer true.
        coordinator.onSessionStatuses(sessions, mapOf("ses_1" to SessionStatus("idle")))
        assertTrue(NotificationPolicy.notificationId(NotificationKind.SessionError, "ses_1") in sink.cancelled)
    }

    @Test
    fun aRefusedPostNeverBreaksTheCallerAndIsNotTracked() = runTest {
        val sink = RecordingSink().apply { failPosts = true }
        val coordinator = LocalNotificationCoordinator(sink)

        // Must not throw even though every post fails: state is server-derived.
        coordinator.onPendingPermissions(listOf(permission))
        coordinator.onPendingQuestions(listOf(question))
        coordinator.onSessionStatuses(
            listOf(SessionSnapshot("ses_1", null, null, null)),
            mapOf("ses_1" to SessionStatus("retry")),
        )

        assertTrue(sink.posts.isEmpty())
        // A later successful post is attempted because the failed one was not tracked.
        sink.failPosts = false
        coordinator.onPendingPermissions(listOf(permission))
        assertEquals(1, sink.posts.size)
    }

    private class RecordingSink : LocalNotificationSink {
        val posts = mutableListOf<AppNotification>()
        val cancelled = mutableListOf<String>()
        var failPosts: Boolean = false

        override suspend fun post(notification: AppNotification) {
            if (failPosts) throw IllegalStateException("notifications are not permitted")
            posts += notification
        }

        override suspend fun cancel(id: String) {
            cancelled += id
        }
    }
}
