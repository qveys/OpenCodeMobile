package org.opencodemobile.android

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.opencodemobile.android.cache.CacheWriteRuntime
import org.opencodemobile.android.chat.ChatRuntime
import org.opencodemobile.android.connection.ConnectionBinder
import org.opencodemobile.android.connection.ConnectionBindingController
import org.opencodemobile.android.di.CHAT_SCOPE_QUALIFIER
import org.opencodemobile.android.di.PERMISSION_SCOPE_QUALIFIER
import org.opencodemobile.android.di.QUESTIONS_SCOPE_QUALIFIER
import org.opencodemobile.android.permission.PermissionRuntime
import org.opencodemobile.android.questions.PendingQuestionsRuntime
import org.opencodemobile.features.connection.ConnectionSetupController
import org.opencodemobile.shared.domain.connection.OpenCodeGateway
import org.opencodemobile.shared.domain.connection.ServerIdentityStore
import org.opencodemobile.shared.domain.connection.ServerIdentityVerifier
import org.opencodemobile.shared.networking.adapter.OpenCodeV2Adapter
import org.opencodemobile.shared.networking.adapter.createOpenCodeGateway
import org.opencodemobile.shared.security.identity.AndroidKeystoreServerIdentityStore
import org.opencodemobile.shared.security.identity.AndroidServerIdentityVerifier
import org.opencodemobile.shared.security.identity.ServerIdentityGate
import org.opencodemobile.shared.security.identity.ServerIdentityPinController
import org.opencodemobile.shared.security.identity.TofuServerIdentityCoordinator
import org.opencodemobile.shared.security.store.AndroidKeystoreSecureStore
import org.opencodemobile.shared.security.store.SecureServerCredentialStore
import org.opencodemobile.shared.security.store.SecureServerProfileStore

/** Koin qualifier for the process scope the live realtime pipeline runs in. */
public const val CONNECTION_SCOPE_QUALIFIER: String = "connectionScope"

/**
 * Android composition root for the connection feature
 * (`docs/ARCHITECTURE.md` §"Server profile import").
 *
 * It is the only place allowed to see every layer (§5.2 constrains the
 * `features` modules and the shared layers, not the app shell), so it assembles
 * the real [OpenCodeGateway] from `shared/networking` + `shared/security` and
 * hands the resulting [ConnectionSetupController] to the UI.
 *
 * Since OPE-176 it also **binds the live connection graph**: on a successful
 * handshake [ConnectionBindingController] publishes the active profile as a
 * `LiveConnection`, and every `Deferred*` seam (session, chat, question,
 * permission, cache) resolves it at call time. The realtime bridges that were
 * inert until then are restarted so `permission.asked` and the other streamed
 * events reach their surfaces.
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

    // One OpenCodeV2Adapter per process, built by the sanctioned factory so the
    // Ktor HttpClient type never reaches this module's classpath. The same pin
    // controller instance feeds the adapter and the platform TLS engine, so the
    // engine-side pin backstop cannot silently disappear. The concrete adapter is
    // registered as well: the live connection builder needs the sanctioned
    // `permissionGateway` / `eventTransport` accessors, which keep the client and
    // the T1 credential gate inside shared/networking.
    single { createOpenCodeGateway(get(), get()) }
    single<OpenCodeGateway> { get<OpenCodeV2Adapter>() }

    single<CoroutineScope>(named(CONNECTION_SCOPE_QUALIFIER)) {
        // Composition root: the single place allowed to pick the dispatcher.
        @Suppress("InjectDispatcher")
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    single { ConnectionBinder() }

    single {
        ConnectionBindingController(
            binder = get(),
            adapter = get<OpenCodeV2Adapter>(),
            processorScope = get(named(CONNECTION_SCOPE_QUALIFIER)),
            onBound = {
                // These bridges resolve the connection at start time, so they were
                // inert until now; a second start wires the realtime ingress.
                getOrNull<PermissionRuntime>()?.start(get(named(PERMISSION_SCOPE_QUALIFIER)))
                getOrNull<PendingQuestionsRuntime>()?.start(get(named(QUESTIONS_SCOPE_QUALIFIER)))
                getOrNull<ChatRuntime>()?.start(get(named(CHAT_SCOPE_QUALIFIER)))
                getOrNull<CacheWriteRuntime>()?.start()
            },
        )
    }

    single {
        val profileStore: SecureServerProfileStore = get()
        val credentialStore: SecureServerCredentialStore = get()
        val identityStore: ServerIdentityStore = get()
        val binding = get<ConnectionBindingController>()
        ConnectionSetupController(
            setup = get(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            credentialProvider = { profile -> credentialStore.credential(profile.id) },
            existingProfileProvider = { profileStore.load() },
            existingFingerprintProvider = { profile -> identityStore.pinnedFingerprint(profile.id) },
            identityConfirmer = { profile, fingerprint ->
                identityStore.storePinnedFingerprint(profile.id, fingerprint)
            },
            onConnected = { profile, _ -> binding.onConnected(profile) },
        )
    }
}
