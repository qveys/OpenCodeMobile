package org.opencodemobile.shared.domain.notification

/**
 * Posts and cancels local notifications (V1-13).
 *
 * The port carries only [AppNotification] plans built by [NotificationPolicy], so
 * an implementation has no way to construct an approving action: the type forbids
 * it. Implementations live in the platform shells (`androidApp` / `iosAppHost`),
 * which are the only layers allowed to touch the OS notification APIs.
 *
 * Posting is **best-effort and state-free**: a notification is a signal, never a
 * store. If a platform cannot post (permission not granted, app killed), the app
 * loses no state — the next server read rebuilds everything (V1-13 criterion 4).
 * Implementations must therefore never throw for a denied permission; they drop
 * the signal.
 */
public interface LocalNotificationSink {
    /** Posts (or replaces) [notification]. */
    public suspend fun post(notification: AppNotification)

    /** Cancels the notification with [id], if it is currently shown. */
    public suspend fun cancel(id: String)

    /**
     * Cancels every notification this app posted, delivered or pending.
     *
     * Used by "Tout effacer" (ADR 0009 §2.1.4) so erased session/permission
     * content does not linger on the lock screen or in the shade. It is
     * deliberately separate from [cancel]: a platform can clear its whole
     * notification surface without the app tracking ids, including notifications
     * posted before the last process restart.
     *
     * Best-effort like [post]: a platform that refuses must not throw, because
     * the notification is a signal and the erase must still complete.
     */
    public suspend fun cancelAll() {
        // No-op by default so a platform (or test) that never posted anything
        // needs no extra code. Real sinks override this.
    }
}

/**
 * Default [LocalNotificationSink] for platforms or tests without a notification
 * implementation. It drops every signal, which is exactly the V1-13 "platform
 * does not permit / not wired" contract: nothing is queued and nothing is lost.
 */
public object NoOpLocalNotificationSink : LocalNotificationSink {
    override suspend fun post(notification: AppNotification): Unit = Unit

    override suspend fun cancel(id: String): Unit = Unit
}
