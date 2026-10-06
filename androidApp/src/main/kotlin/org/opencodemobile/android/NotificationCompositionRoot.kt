package org.opencodemobile.android

import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module
import org.opencodemobile.android.notification.AndroidLocalNotificationSink
import org.opencodemobile.shared.application.notification.LocalNotificationCoordinator
import org.opencodemobile.shared.domain.notification.LocalNotificationSink

/**
 * Android composition root for the local-notification surface (V1-13).
 *
 * Like the connection root, this is the only place on Android allowed to bind
 * the platform [LocalNotificationSink]; the shared coordinator depends solely on
 * the domain port. The channel ids and action projection are fixed by
 * [org.opencodemobile.android.notification.AndroidNotificationContent], whose
 * unit test proves no approving action is ever built.
 */
public val notificationCompositionModule: Module = module {
    single<LocalNotificationSink> { AndroidLocalNotificationSink(androidContext()) }
    single { LocalNotificationCoordinator(get()) }
}
