package org.opencodemobile.shared.application.notification

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.opencodemobile.shared.domain.event.SessionSnapshot
import org.opencodemobile.shared.domain.event.SessionStatus
import org.opencodemobile.shared.domain.interaction.PendingQuestion
import org.opencodemobile.shared.domain.notification.AppNotification
import org.opencodemobile.shared.domain.notification.LocalNotificationSink
import org.opencodemobile.shared.domain.notification.NotificationKind
import org.opencodemobile.shared.domain.notification.NotificationPolicy
import org.opencodemobile.shared.domain.notification.NoOpLocalNotificationSink
import org.opencodemobile.shared.domain.permission.PermissionRequest

/**
 * Turns app state into local notifications (V1-13) and keeps the notification
 * shade in step with the server-derived state.
 *
 * Design rules, all of them enforced here rather than in the platform sinks:
 *
 * - **Best-effort.** A notification is a signal, not state. The coordinator never
 *   throws when a platform refuses to post; it drops the signal. The caller's
 *   state (pending permissions/questions, session statuses) is the server's and is
 *   rebuilt on the next read, so a missing notification loses nothing (V1-13
 *   criterion 4).
 * - **No approve, ever.** Every plan comes from [NotificationPolicy], whose
 *   actions are the [org.opencodemobile.shared.domain.notification.NotificationAction]
 *   enum — there is no approving member.
 * - **Idempotent.** A plan already posted with the same id is not posted twice, so
 *   a reconcile that re-delivers the same pending set does not spam the shade.
 */
public class LocalNotificationCoordinator(
    private val sink: LocalNotificationSink = NoOpLocalNotificationSink,
) {
    private val lock = Mutex()

    /** Ids currently posted, and the kind that owns them, keyed by plan id. */
    private val posted = mutableMapOf<String, NotificationKind>()

    /** Last observed status type per session, so a `busy → idle` edge is detectable. */
    private val previousSessionTypes = mutableMapOf<String, String>()

    /**
     * Replaces the pending-permission notifications with one notification per
     * request in [pending]; a request that is no longer pending is cancelled.
     */
    public suspend fun onPendingPermissions(pending: List<PermissionRequest>) {
        reconcile(
            kind = NotificationKind.PermissionPending,
            desired = pending.map(NotificationPolicy::permissionFor),
        )
    }

    /**
     * Replaces the pending-question notifications with one notification per
     * question in [questions]; a question the server no longer lists is cancelled.
     */
    public suspend fun onPendingQuestions(questions: List<PendingQuestion>) {
        reconcile(
            kind = NotificationKind.QuestionPending,
            desired = questions.map(NotificationPolicy::questionFor),
        )
    }

    /**
     * Posts the completion / error notifications for [statuses], using the
     * previous status of each session to detect a `busy → idle` completion.
     *
     * A session that goes `busy` again clears its finished/error notification, so
     * a later completion is surfaced afresh.
     */
    public suspend fun onSessionStatuses(
        sessions: List<SessionSnapshot>,
        statuses: Map<String, SessionStatus>,
    ) {
        val titles = sessions.associate { it.id to it.title }
        lock.withLock {
            for ((sessionId, status) in statuses) {
                applySessionTransition(
                    sessionId = sessionId,
                    title = titles[sessionId],
                    previousType = previousSessionTypes[sessionId],
                    currentType = status.type,
                )
            }
            previousSessionTypes.keys.retainAll(statuses.keys)
            for ((sessionId, status) in statuses) {
                previousSessionTypes[sessionId] = status.type
            }
        }
    }

    /** Cancels the notification with [id], if it is currently shown. */
    public suspend fun cancel(id: String) {
        lock.withLock { cancelLocked(id) }
    }

    /**
     * Cancels every notification this coordinator tracks **and** any the app
     * posted before the last restart, then forgets the tracking state.
     *
     * Used by "Tout effacer" (ADR 0009 §2.1.4): after this returns no
     * session/permission content lingers on the lock screen or in the shade.
     * The platform [sink] clears its whole surface, which is what covers the
     * notifications a fresh process has no record of.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // a failed clear leaves a stale signal, never broken state
    public suspend fun cancelAll() {
        lock.withLock {
            posted.clear()
            previousSessionTypes.clear()
            try {
                sink.cancelAll()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // best-effort: a platform that refuses to clear must not break
                // the erase; the notification is a signal, not state.
            }
        }
    }

    private suspend fun reconcile(kind: NotificationKind, desired: List<AppNotification>) {
        lock.withLock {
            val desiredIds = desired.map { it.id }.toSet()
            posted
                .filterValues { it == kind }
                .keys
                .filterNot { it in desiredIds }
                .toList()
                .forEach { cancelLocked(it) }
            for (notification in desired) {
                if (posted[notification.id] == kind) continue
                postLocked(notification)
            }
        }
    }

    private suspend fun applySessionTransition(
        sessionId: String,
        title: String?,
        previousType: String?,
        currentType: String,
    ) {
        val completedId = NotificationPolicy.notificationId(NotificationKind.SessionCompleted, sessionId)
        val errorId = NotificationPolicy.notificationId(NotificationKind.SessionError, sessionId)

        val completed = NotificationPolicy.sessionCompletedFor(sessionId, title, previousType, currentType)
        val error = NotificationPolicy.sessionErrorFor(sessionId, title, currentType)

        when {
            currentType == SESSION_BUSY -> {
                cancelLocked(completedId)
                cancelLocked(errorId)
            }

            error != null -> {
                cancelLocked(completedId)
                postLocked(error)
            }

            completed != null -> {
                cancelLocked(errorId)
                postLocked(completed)
            }

            previousType == SESSION_RETRY -> cancelLocked(errorId)
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // a platform that refuses to post must never break the caller
    private suspend fun postLocked(notification: AppNotification) {
        try {
            sink.post(notification)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            return
        }
        posted[notification.id] = notification.kind
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // a failed cancel leaves a stale signal, never broken state
    private suspend fun cancelLocked(id: String) {
        posted.remove(id)
        try {
            sink.cancel(id)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // best-effort
        }
    }

    private companion object {
        private const val SESSION_BUSY: String = "busy"
        private const val SESSION_RETRY: String = "retry"
    }
}
