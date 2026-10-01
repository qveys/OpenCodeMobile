package org.opencodemobile.android.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.opencodemobile.android.permission.AndroidPermissionNotifier
import org.opencodemobile.android.permission.DeferredPendingPermissionStore
import org.opencodemobile.android.permission.DeferredPermissionEventDecoder
import org.opencodemobile.android.permission.DeferredPermissionPort
import org.opencodemobile.android.permission.PermissionConnection
import org.opencodemobile.android.permission.PermissionHostActivity
import org.opencodemobile.android.connection.ConnectionBinder
import org.opencodemobile.android.permission.PermissionRuntime
import org.opencodemobile.features.permissions.PermissionsPresenter
import org.opencodemobile.shared.application.permission.PermissionCoordinator
import org.opencodemobile.shared.application.permission.PermissionRealtimeBridge
import org.opencodemobile.shared.data.cache.CacheStack
import org.opencodemobile.shared.data.permission.DeferredCachePendingPermissionStore
import org.opencodemobile.shared.domain.cache.SessionCache
import org.opencodemobile.shared.domain.permission.BiometricAuthenticator
import org.opencodemobile.shared.domain.permission.PermissionEventDecoder
import org.opencodemobile.shared.domain.permission.PermissionNotifier
import org.opencodemobile.shared.domain.permission.PermissionPort
import org.opencodemobile.shared.domain.permission.PendingPermissionStore
import org.opencodemobile.shared.security.biometric.AndroidBiometricAuthenticator

/** Koin qualifier for the app-wide scope the permission presenter lives in. */
public const val PERMISSION_SCOPE_QUALIFIER: String = "permissionScope"

/**
 * The V1-06 permission surface, wired into the app shell (OPE-173 / H2).
 *
 * It assembles the real presenter/coordinator graph the security review asked for,
 * so the gates are enforced in a running build, not only in library tests:
 *
 * - the coordinator enforces the offline (D8), foreground, content-binding and
 *   biometric gates before any approval reaches the wire,
 * - [AndroidBiometricAuthenticator] is the real `BiometricPrompt` / device-credential
 *   gate, bound to the current `FragmentActivity`,
 * - [AndroidPermissionNotifier] posts exactly `PermissionPolicy.notificationFor`
 *   (no approve action, tap opens the confirmation screen),
 * - the pending set is persisted in the encrypted cache so it survives an app kill.
 *
 * **Live connection.** The connection composition root
 * (`connectionCompositionModule`) binds a [ConnectionBinder] on a successful
 * handshake; the resolvers below read the active [PermissionConnection] at call
 * time, so the surface reaches the server as soon as it is live. Before a
 * connection exists the resolvers return null and the surface is inert and
 * fail-closed (see [UnavailablePermissionPort]); the coordinator is offline, so
 * nothing can be approved. The `MutationGate` is resolved by type from the cache
 * composition root (`cacheModule`).
 */
public val permissionModule: Module = module {
    single<CoroutineScope>(named(PERMISSION_SCOPE_QUALIFIER)) {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    single<BiometricAuthenticator> {
        AndroidBiometricAuthenticator { PermissionHostActivity.current }
    }

    single<PermissionNotifier> { AndroidPermissionNotifier(androidContext()) }

    single<PermissionPort> {
        DeferredPermissionPort { getOrNull<ConnectionBinder>()?.permission() }
    }

    single<PermissionEventDecoder> {
        DeferredPermissionEventDecoder { getOrNull<ConnectionBinder>()?.permission() }
    }

    single<PendingPermissionStore> {
        DeferredPendingPermissionStore {
            val connection = getOrNull<ConnectionBinder>()?.permission()
            val cache = getOrNull<SessionCache>()
            val cacheStack = getOrNull<CacheStack>()
            if (connection != null && cache != null && cacheStack != null) {
                DeferredCachePendingPermissionStore(cache, cacheStack::writer, connection.serverId)
            } else {
                null
            }
        }
    }

    // The offline gate (D8) is bound by the cache composition root; resolved by type.
    single { PermissionCoordinator(get(), get(), get(), get(), get()) }

    single { PermissionsPresenter(get(), get(named(PERMISSION_SCOPE_QUALIFIER))) }

    single {
        val coordinator = runCatching { getOrNull<PermissionCoordinator>() }.getOrNull()
        PermissionRuntime(coordinator) {
            val connection = getOrNull<ConnectionBinder>()?.permission()
            if (coordinator != null && connection != null) {
                PermissionRealtimeBridge(connection.source, connection.decoder, coordinator)
            } else {
                null
            }
        }
    }
}
