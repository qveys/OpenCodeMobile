package org.opencodemobile.android.connection

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.opencodemobile.shared.application.permission.PermissionCoordinator
import org.opencodemobile.shared.application.permission.PermissionRealtimeBridge
import org.opencodemobile.shared.application.permission.PermissionSubmitResult
import org.opencodemobile.shared.domain.cache.MutationGate
import org.opencodemobile.shared.domain.connection.ServerFingerprint
import org.opencodemobile.shared.domain.connection.ServerIdentityStore
import org.opencodemobile.shared.domain.connection.ServerIdentityVerifier
import org.opencodemobile.shared.domain.connection.ServerProfile
import org.opencodemobile.shared.domain.permission.BiometricAuthenticator
import org.opencodemobile.shared.domain.permission.BiometricResult
import org.opencodemobile.shared.domain.permission.NoOpPermissionNotifier
import org.opencodemobile.shared.domain.permission.PermissionDecision
import org.opencodemobile.shared.domain.permission.PermissionRequest
import org.opencodemobile.shared.domain.permission.PendingPermissionStore
import org.opencodemobile.shared.networking.adapter.OpenCodeV2Adapter
import org.opencodemobile.shared.security.identity.ServerIdentityGate
import org.opencodemobile.shared.security.identity.ServerIdentityPinController
import org.opencodemobile.shared.security.identity.TofuServerIdentityCoordinator
import org.opencodemobile.shared.testsupport.MockOpenCodeScenario
import org.opencodemobile.shared.testsupport.MockOpenCodeServer
import org.opencodemobile.shared.testsupport.OpenCodeFixtures

private class EmptyIdentityStore : ServerIdentityStore {
    override suspend fun pinnedFingerprint(profileId: String): ServerFingerprint? = null
    override suspend fun storePinnedFingerprint(profileId: String, fingerprint: ServerFingerprint): Unit = Unit
    override suspend fun clearPinnedFingerprint(profileId: String): Unit = Unit
}

private class NeverProbedVerifier : ServerIdentityVerifier {
    override suspend fun presentedFingerprint(profile: ServerProfile): ServerFingerprint =
        error("a plaintext mock profile has no certificate; the verifier must never be reached")
}

private class RecordingPendingPermissionStore : PendingPermissionStore {
    private var saved: List<PermissionRequest> = emptyList()

    val persisted: List<PermissionRequest> get() = saved

    override suspend fun load(): List<PermissionRequest> = saved

    override suspend fun save(requests: List<PermissionRequest>) {
        saved = requests
    }
}

private object AllowMutations : MutationGate {
    override fun mutationsAllowed(): Boolean = true
}

private class ScriptedBiometricAuthenticator(
    var result: BiometricResult = BiometricResult.Succeeded,
) : BiometricAuthenticator {
    var calls: Int = 0

    override suspend fun authenticate(reason: String): BiometricResult {
        calls += 1
        return result
    }
}

/**
 * App-shell acceptance for OPE-176: the connection graph is bound from the real
 * `OpenCodeV2Adapter` and every L2/L3 seam resolves it.
 *
 * It drives the assembled app path one layer above the previous
 * `PermissionIngressMockServerTest`: `ConnectionBindingController` connects a
 * real adapter to `MockOpenCodeServer`, publishes a `LiveConnection`, and the
 * permission surface is fed through the seam the app shell binds — not through a
 * hand-built gateway. The sessions seam is proven on the same connection.
 *
 * Runs on [Dispatchers.Default] so the mock's real inter-event delays are
 * honoured, like the realtime integration tests.
 */
class LiveConnectionBindingTest {

    private val profile = ServerProfile(
        id = "mock-profile",
        host = "localhost",
        port = 4096,
        tls = ServerProfile.TlsMode.PlaintextHttp,
    )

    private val servers = mutableListOf<MockOpenCodeServer>()
    private val scopes = mutableListOf<CoroutineScope>()

    @AfterTest
    fun tearDown() {
        scopes.forEach { it.cancel() }
        servers.forEach { it.stop() }
    }

    private fun startServer(scenario: MockOpenCodeScenario): MockOpenCodeServer =
        MockOpenCodeServer(scenario = scenario).start().also { servers += it }

    private fun newScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }

    private suspend fun awaitUntil(timeoutMillis: Long = 4_000L, condition: () -> Boolean) {
        withTimeout(timeoutMillis) {
            while (!condition()) delay(5L)
        }
    }

    private fun adapterFor(server: MockOpenCodeServer): OpenCodeV2Adapter {
        val pin = ServerIdentityPinController()
        val gate = ServerIdentityGate(
            TofuServerIdentityCoordinator(EmptyIdentityStore(), NeverProbedVerifier()),
        )
        return OpenCodeV2Adapter(server.client, gate, pin)
    }

    /**
     * The graph under test: a [ConnectionBinder] filled by the app-shell
     * [ConnectionBindingController], plus the permission coordinator/bridge bound
     * to the resolved seam.
     */
    private class BoundApp(
        val binder: ConnectionBinder,
        val controller: ConnectionBindingController,
        val store: RecordingPendingPermissionStore,
        val biometric: ScriptedBiometricAuthenticator,
        val coordinator: PermissionCoordinator,
        val bridge: PermissionRealtimeBridge,
    )

    private suspend fun connectAndBind(
        server: MockOpenCodeServer,
        scope: CoroutineScope,
        store: RecordingPendingPermissionStore = RecordingPendingPermissionStore(),
    ): BoundApp {
        val adapter = adapterFor(server)
        adapter.connect(profile, null)

        val binder = ConnectionBinder()
        val controller = ConnectionBindingController(binder, adapter, scope)
        controller.onConnected(profile)

        val permission = assertNotNull(binder.permission(), "the permission seam must be bound")
        val biometric = ScriptedBiometricAuthenticator()
        val coordinator = PermissionCoordinator(
            port = permission.port,
            store = store,
            mutationGate = AllowMutations,
            biometricAuthenticator = biometric,
            notifier = NoOpPermissionNotifier,
        )
        val bridge = PermissionRealtimeBridge(permission.source, permission.decoder, coordinator)
        scope.launch { coordinator.start() }
        bridge.start(scope)
        return BoundApp(binder, controller, store, biometric, coordinator, bridge)
    }

    @Test
    fun boundConnectionFeedsPermissionAskedToTheSurfaceAndPersistsIt() = runTest {
        val server = startServer(MockOpenCodeScenario.PermissionRequest)
        val scope = newScope()
        val app = connectAndBind(server, scope)
        val requestId = OpenCodeFixtures.PERMISSION_REQUEST_ID

        withContext(Dispatchers.Default) {
            awaitUntil { app.coordinator.state.value.request(requestId) != null }

            val state = app.coordinator.state.value
            assertTrue(state.bannerVisible, "a pending permission must raise the banner")
            val request = state.request(requestId)!!
            assertEquals("bash", request.tool)
            assertEquals(profile.id, app.binder.permission()!!.serverId)
            assertTrue(app.store.persisted.any { it.id == requestId })
        }
    }

    @Test
    fun boundSessionSeamResolvesTheAdapterAgainstTheServer() = runTest {
        val server = startServer(MockOpenCodeScenario.Streaming)
        val scope = newScope()
        val app = connectAndBind(server, scope)

        withContext(Dispatchers.Default) {
            val session = assertNotNull(app.binder.session(), "the session seam must be bound")
            assertEquals(profile.id, session.serverId)
            assertEquals(DEFAULT_PROJECT_ID, session.projectId)
            val sessions = session.gateway.listSessions(null)
            assertEquals(OpenCodeFixtures.sessions.map { it.id }, sessions.map { it.id })
        }
    }

    @Test
    fun rebindingAfterAKillBringsTheBannerBackWithNoImplicitApproval() = runTest {
        val server = startServer(MockOpenCodeScenario.PermissionRequest)
        val requestId = OpenCodeFixtures.PERMISSION_REQUEST_ID
        val sharedStore = RecordingPendingPermissionStore()

        val firstScope = newScope()
        val first = connectAndBind(server, firstScope, sharedStore)
        withContext(Dispatchers.Default) {
            awaitUntil { first.coordinator.state.value.request(requestId) != null }
        }

        // Kill: drop the connection (stops the pipeline) and the scope.
        first.controller.onDisconnected()
        firstScope.cancel()
        assertEquals(emptyList(), server.permissionReplies, "restoring a pending request never approves it")

        val secondScope = newScope()
        val second = connectAndBind(server, secondScope, sharedStore)
        withContext(Dispatchers.Default) {
            awaitUntil { second.coordinator.state.value.request(requestId) != null }
            assertTrue(second.coordinator.state.value.bannerVisible, "the banner must come back")
        }
        assertEquals(emptyList(), server.permissionReplies, "the restored request stays pending")
    }

    @Test
    fun approvalThroughTheBoundConnectionNeedsTheForegroundGatesAndReachesTheWireOnce() = runTest {
        val server = startServer(MockOpenCodeScenario.PermissionRequest)
        val scope = newScope()
        val app = connectAndBind(server, scope)
        val requestId = OpenCodeFixtures.PERMISSION_REQUEST_ID

        withContext(Dispatchers.Default) {
            awaitUntil { app.coordinator.state.value.request(requestId) != null }
            val fingerprint = app.coordinator.state.value.request(requestId)!!.contentFingerprint

            assertEquals(
                PermissionSubmitResult.NotForegrounded,
                app.coordinator.approve(requestId, PermissionDecision.Once, fingerprint),
            )
            app.coordinator.onForegroundChanged(true)
            app.coordinator.arm(requestId)
            app.biometric.result = BiometricResult.Succeeded
            assertEquals(
                PermissionSubmitResult.Accepted,
                app.coordinator.approve(requestId, PermissionDecision.Once, fingerprint),
            )
            assertEquals(listOf("once"), server.permissionReplies.map { it.reply })
            assertEquals(emptyList(), app.coordinator.state.value.pending)
        }
    }
}
