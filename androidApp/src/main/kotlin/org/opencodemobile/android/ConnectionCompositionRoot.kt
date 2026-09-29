package org.opencodemobile.android

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module
import org.opencodemobile.features.connection.ConnectionSetupController
import org.opencodemobile.shared.domain.connection.OpenCodeGateway
import org.opencodemobile.shared.domain.connection.ServerIdentityStore
import org.opencodemobile.shared.domain.connection.ServerIdentityVerifier
import org.opencodemobile.shared.networking.adapter.createOpenCodeGateway
import org.opencodemobile.shared.security.identity.AndroidKeystoreServerIdentityStore
import org.opencodemobile.shared.security.identity.AndroidServerIdentityVerifier
import org.opencodemobile.shared.security.identity.ServerIdentityGate
import org.opencodemobile.shared.security.identity.ServerIdentityPinController
import org.opencodemobile.shared.security.identity.TofuServerIdentityCoordinator
import org.opencodemobile.shared.security.store.AndroidKeystoreSecureStore
import org.opencodemobile.shared.security.store.SecureServerCredentialStore
import org.opencodemobile.shared.security.store.SecureServerProfileStore

/**
 * Android composition root for the connection feature
 * (`docs/ARCHITECTURE.md` §"Server profile import").
 *
 * It is the only place allowed to see every layer (§5.2 constrains the
 * `features` modules and the shared layers, not the app shell), so it assembles
 * the real [OpenCodeGateway] from `shared/networking` + `shared/security` and
 * hands the resulting [ConnectionSetupController] to the UI.
 *
 * The camera port is deliberately *not* bound here: it is activity-scoped
 * (`AndroidQrCodeScanner` registers an `ActivityResultLauncher`), so
 * `MainActivity` constructs it and passes it to `ConnectionSetupScreen`
 * together with the injected controller.
 */
public val connectionCompositionModule: Module = module {
    // PKI/credential state stays in the Keystore-backed stores (B2), each in
    // its own namespace so sibling secrets cannot be read across stores.
    single { SecureServerProfileStore(AndroidKeystoreSecureStore(androidContext(), "server-profile")) }
    single { SecureServerCredentialStore(AndroidKeystoreSecureStore(androidContext(), "server-credential")) }
    single<ServerIdentityStore> { AndroidKeystoreServerIdentityStore(androidContext()) }
    single<ServerIdentityVerifier> { AndroidServerIdentityVerifier() }

    single { ServerIdentityPinController() }
    single { TofuServerIdentityCoordinator(get(), get()) }
    single { ServerIdentityGate(get()) }

    // One OpenCodeGateway per process, built by the sanctioned factory so the
    // Ktor HttpClient type never reaches this module's classpath. The same pin
    // controller instance feeds the adapter and the platform TLS engine, so the
    // engine-side pin backstop cannot silently disappear.
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
