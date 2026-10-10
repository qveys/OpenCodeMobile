@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.opencodemobile.ios

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.ExperimentalComposeApi
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.LocalUIViewController
import androidx.compose.ui.platform.AccessibilitySyncOptions
import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.compose.koinInject
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.mp.KoinPlatform
import org.opencodemobile.design.system.OpenCodeContext
import org.opencodemobile.design.system.OpenCodeTheme
import org.koin.dsl.module
import org.opencodemobile.features.connection.ConnectionModule
import org.opencodemobile.features.connection.ConnectionSetupController
import org.opencodemobile.features.connection.ConnectionSetupScreen
import org.opencodemobile.features.connection.IosQrCodeScanner
import org.opencodemobile.features.settings.LocalAccessSettingsHost
import org.opencodemobile.features.settings.SettingsModule
import org.opencodemobile.shared.domain.connection.OpenCodeGateway
import org.opencodemobile.shared.domain.connection.ServerIdentityStore
import org.opencodemobile.shared.domain.connection.ServerIdentityVerifier
import org.opencodemobile.shared.domain.localaccess.LocalAccessSettingsStore
import org.opencodemobile.shared.networking.adapter.createOpenCodeGateway
import org.opencodemobile.shared.security.identity.IosKeychainServerIdentityStore
import org.opencodemobile.shared.security.identity.IosServerIdentityVerifier
import org.opencodemobile.shared.security.identity.ServerIdentityGate
import org.opencodemobile.shared.security.identity.ServerIdentityPinController
import org.opencodemobile.shared.security.identity.TofuServerIdentityCoordinator
import org.opencodemobile.shared.security.localaccess.IosLocalAccessSettingsStore
import org.opencodemobile.shared.security.store.IosKeychainSecureStore
import org.opencodemobile.shared.security.store.SecureServerCredentialStore
import org.opencodemobile.shared.security.store.SecureServerProfileStore
import platform.UIKit.UIViewController

/**
 * iOS composition root for the connection feature, the counterpart of
 * `androidApp`'s `ConnectionCompositionRoot`.
 *
 * It is the only place on the iOS side allowed to see every layer
 * (§5.2 constrains the `features` modules and the shared layers, not the app
 * shell), so it assembles the real [OpenCodeGateway] from `shared/networking` +
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
internal val iosConnectionCompositionModule: Module = module {
    // PKI/credential state stays in the Keychain-backed stores (B2), each in
    // its own service namespace so sibling secrets cannot be read across stores.
    single { SecureServerProfileStore(IosKeychainSecureStore("server-profile")) }
    single { SecureServerCredentialStore(IosKeychainSecureStore("server-credential")) }
    single<ServerIdentityStore> { IosKeychainServerIdentityStore() }
    single<ServerIdentityVerifier> { IosServerIdentityVerifier() }

    single { ServerIdentityPinController() }
    single { TofuServerIdentityCoordinator(get(), get()) }
    single { ServerIdentityGate(get()) }

    // §7.3 device-local access preferences (app-switcher masking default,
    // optional biometrics, optional capture blocking). NSUserDefaults-backed,
    // never the Keychain: these are preferences, not secrets.
    single<LocalAccessSettingsStore> { IosLocalAccessSettingsStore() }

    // One OpenCodeGateway per process, built by the sanctioned factory (the same
    // one Android uses) so the Ktor HttpClient type never reaches this module's
    // classpath. The factory installs the JSON ContentNegotiation the generated
    // client's `body()` calls need; the platform factory behind it applies the
    // outbound transport policy. The same pin controller instance also feeds the
    // platform TLS engine, so the pin backstop cannot silently disappear.
    single<OpenCodeGateway> { createOpenCodeGateway(get(), get()) }

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
        modules(
            iosConnectionCompositionModule,
            iosEraseEverythingCompositionModule,
            ConnectionModule.koinModule,
            SettingsModule.koinModule,
        )
    }
}

/**
 * §7.3: whether the Swift shell must mask the app-switcher snapshot.
 *
 * SwiftUI owns the scene phase on iOS, so the cover is installed by
 * `ContentView`; it asks Kotlin for the persisted preference through this
 * bridge. It reads the same [LocalAccessSettingsStore] the Compose settings
 * screen writes, so the two platforms stay consistent.
 */
public fun multitaskMaskingEnabled(): Boolean {
    startIosKoin()
    return KoinPlatform.getKoin().get<LocalAccessSettingsStore>().load().multitaskMaskingEnabled
}

/**
 * Builds the Compose UIViewController the Swift shell embeds: the connection
 * setup screen hosted by Koin, with the AVFoundation [IosQrCodeScanner] built
 * from the Compose host controller via `LocalUIViewController`.
 *
 * Call [startIosKoin] first. The returned controller owns the Compose
 * lifecycle; Swift only presents it.
 *
 * @param accessibilitySyncAlways builds the Compose semantics tree even without
 *   an assistive service running. It is only set by the UI test (via the
 *   debug-only `-OPEUIAccessibility` launch argument): XCUITest is not a
 *   VoiceOver client, so with the production default
 *   ([AccessibilitySyncOptions.WhenRequiredByAccessibilityServices]) it would
 *   only ever see the raw view hierarchy, not the rendered semantics.
 */
@OptIn(ExperimentalComposeApi::class)
public fun connectionSetupViewController(
    accessibilitySyncAlways: Boolean = false,
): UIViewController = ComposeUIViewController(
    configure = {
        if (accessibilitySyncAlways) {
            // Leave the production default (WhenRequiredByAccessibilityServices)
            // untouched otherwise.
            accessibilitySyncOptions = AccessibilitySyncOptions.Always(null)
        }
    },
) {
    OpenCodeTheme(
        context = if (isSystemInDarkTheme()) OpenCodeContext.ChromeDark else OpenCodeContext.Chrome,
    ) {
        // The Swift shell draws edge to edge (`.ignoresSafeArea()`), so the
        // system-bar/notch insets are applied here, inside the opaque Surface.
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                // §7.3: the settings entry point is the same shared Compose code as
                // on Android; this shell only supplies the connection content.
                val controller: ConnectionSetupController = koinInject()
                val setupState by controller.state.collectAsState()
                LocalAccessSettingsHost(showHeader = !setupState.isReviewing) {
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
    }
}

/**
 * Acceptance-only seam (OPE-169, used by `ConnectionScreenUITests`).
 *
 * Drives [ConnectionSetupController.submitScannedPayload] with a payload the
 * simulator cannot supply through the camera, so the QR import path can be
 * exercised end to end. The Swift shell calls it only when the
 * `-OPEQRPayload <value>` launch argument is present (and only in `DEBUG`), so
 * a production launch can never reach it. It reuses the process-wide Koin
 * controller, so the Compose screen observes exactly the state the real scanner
 * would produce.
 */
public fun submitScannedPayloadForAcceptance(payload: String) {
    startIosKoin()
    KoinPlatform.getKoin().get<ConnectionSetupController>().submitScannedPayload(payload)
}
