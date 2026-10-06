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
