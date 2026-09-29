@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.opencodemobile.ios

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.LocalUIViewController
import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.compose.koinInject
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.module
import org.opencodemobile.features.connection.ConnectionModule
import org.opencodemobile.features.connection.ConnectionSetupController
import org.opencodemobile.features.connection.ConnectionSetupScreen
import org.opencodemobile.features.connection.IosQrCodeScanner
import org.opencodemobile.shared.domain.connection.OpenCodeGateway
import org.opencodemobile.shared.domain.connection.ServerIdentityStore
import org.opencodemobile.shared.domain.connection.ServerIdentityVerifier
import org.opencodemobile.shared.networking.adapter.OpenCodeV2Adapter
import org.opencodemobile.shared.networking.adapter.createOpenCodeHttpClient
import org.opencodemobile.shared.networking.adapter.installHttpMethodPolicy
import org.opencodemobile.shared.networking.logging.installSanitizingLogging
import org.opencodemobile.shared.security.identity.IosKeychainServerIdentityStore
import org.opencodemobile.shared.security.identity.IosServerIdentityVerifier
import org.opencodemobile.shared.security.identity.ServerIdentityGate
import org.opencodemobile.shared.security.identity.ServerIdentityPinController
import org.opencodemobile.shared.security.identity.TofuServerIdentityCoordinator
import org.opencodemobile.shared.security.store.IosKeychainSecureStore
import org.opencodemobile.shared.security.store.SecureServerCredentialStore
import org.opencodemobile.shared.security.store.SecureServerProfileStore
import platform.UIKit.UIViewController

/**
 * iOS composition root for the connection feature, the counterpart of
 * `androidApp`'s `ConnectionCompositionRoot`.
 *
 * It is the only place on the iOS side allowed to see every layer
 * (§5.2 constrains `features/*` and the shared layers, not the app shell), so
 * it assembles the real [OpenCodeGateway] from `shared/networking` +
 * `shared/security` and hands the resulting [ConnectionSetupController] to the
 * UI. The Koin graph behind the controller lives in
 * [iosConnectionCompositionModule]; the Swift shell only calls [startIosKoin]
 * and embeds [connectionSetupViewController].
 *
 * The camera port is deliberately *not* bound in Koin: [IosQrCodeScanner] is
 * built at the Compose call site from the view controller that hosts the UI,
 * exactly as `androidApp` builds the activity-scoped `AndroidQrCodeScanner`
 * outside Koin.
 */
public val iosConnectionCompositionModule: Module = module {
    // PKI/credential state stays in the Keychain-backed stores (B2), each in
    // its own service namespace so sibling secrets cannot be read across stores.
    single { SecureServerProfileStore(IosKeychainSecureStore("server-profile")) }
    single { SecureServerCredentialStore(IosKeychainSecureStore("server-credential")) }
    single<ServerIdentityStore> { IosKeychainServerIdentityStore() }
    single<ServerIdentityVerifier> { IosServerIdentityVerifier() }

    single { ServerIdentityPinController() }
    single { TofuServerIdentityCoordinator(get(), get()) }
    single { ServerIdentityGate(get()) }

    // One HttpClient per process, sharing the same pin controller as the
    // adapter so the engine-side pin backstop cannot silently disappear.
    single {
        createOpenCodeHttpClient(get()) {
            installSanitizingLogging()
            installHttpMethodPolicy()
        }
    }
    single<OpenCodeGateway> { OpenCodeV2Adapter(get(), get(), get()) }

    single {
        val profileStore: SecureServerProfileStore = get()
        val credentialStore: SecureServerCredentialStore = get()
        val identityStore: ServerIdentityStore = get()
        ConnectionSetupController(
            setup = get(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            credentialProvider = { profile -> credentialStore.credential(profile.id) },
            existingProfileProvider = { profileStore.load() },
            existingFingerprintProvider = { profile -> identityStore.pinnedFingerprint(profile.id) },
            identityConfirmer = { profile, fingerprint ->
                identityStore.storePinnedFingerprint(profile.id, fingerprint)
            },
        )
    }
}

private var koinStarted = false

/**
 * Starts the process-wide Koin graph. The Swift shell calls this once from
 * `IOSApp.init`, the same place `androidApp` starts Koin in
 * `OpenCodeMobileApp.onCreate`. It is idempotent so a SwiftUI scene rebuild
 * cannot start a second graph.
 */
public fun startIosKoin() {
    if (koinStarted) return
    koinStarted = true
    startKoin {
        modules(iosConnectionCompositionModule, ConnectionModule.koinModule)
    }
}

/**
 * Builds the Compose UIViewController the Swift shell embeds: the connection
 * setup screen hosted by Koin, with the AVFoundation [IosQrCodeScanner] built
 * from the Compose host controller via `LocalUIViewController`.
 *
 * Call [startIosKoin] first. The returned controller owns the Compose
 * lifecycle; Swift only presents it.
 */
public fun connectionSetupViewController(): UIViewController = ComposeUIViewController {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            val controller: ConnectionSetupController = koinInject()
            // The controller hosting this Compose hierarchy is the presenter
            // the scanner presents its full-screen capture from. It is read
            // here (composition time) and captured for the later scan gesture.
            val hostViewController = LocalUIViewController.current
            val scanner = remember(hostViewController) {
                IosQrCodeScanner(presenter = { hostViewController })
            }
            ConnectionSetupScreen(controller = controller, scanner = scanner)
        }
    }
}
