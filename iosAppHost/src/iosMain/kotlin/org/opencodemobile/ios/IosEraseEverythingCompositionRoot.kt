@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.opencodemobile.ios

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.dsl.module
import org.opencodemobile.ios.notification.IosLocalNotificationSink
import org.opencodemobile.shared.application.erasure.EraseEverythingCoordinator
import org.opencodemobile.shared.application.notification.LocalNotificationCoordinator
import org.opencodemobile.shared.domain.cache.EmptyLocalCacheEraser
import org.opencodemobile.shared.domain.cache.LocalCacheEraser
import org.opencodemobile.shared.domain.connection.ServerIdentityStore
import org.opencodemobile.shared.domain.notification.LocalNotificationSink
import org.opencodemobile.shared.domain.permission.BiometricAuthenticator
import org.opencodemobile.shared.security.biometric.IosBiometricAuthenticator
import org.opencodemobile.shared.security.store.SecureServerCredentialStore
import org.opencodemobile.shared.security.store.SecureServerProfileStore

/**
 * iOS composition root for "Tout effacer" (OPE-275 / ADR 0009), the counterpart
 * of `androidApp`'s `eraseEverythingCompositionModule`.
 *
 * It binds the same domain ports the coordinator consumes. The profile,
 * credential and identity stores are the Keychain-backed singletons the
 * connection root already bound.
 *
 * Cache erasure is the one platform difference. The iOS cache stack
 * (Data Protection, `shared/data` CacheStack) is not composed in this build yet,
 * so no cache artifact exists on disk and [EmptyLocalCacheEraser] is honest:
 * there is nothing to erase. When that stack lands it must bind a real
 * [LocalCacheEraser] that deletes the SQLDelight database, its `-wal`/`-shm`
 * sidecars and the Data Protection key — the coordinator already calls the port,
 * so only this binding changes.
 */
internal val iosEraseEverythingCompositionModule: Module = module {
    single<BiometricAuthenticator> { IosBiometricAuthenticator() }
    single<LocalNotificationSink> { IosLocalNotificationSink() }
    single { LocalNotificationCoordinator(get()) }
    single<LocalCacheEraser> { EmptyLocalCacheEraser }

    single { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }

    single {
        EraseEverythingCoordinator(
            profileStore = get<SecureServerProfileStore>(),
            credentialStore = get<SecureServerCredentialStore>(),
            identityStore = get<ServerIdentityStore>(),
            cacheEraser = get(),
            notifications = get(),
        )
    }
}
