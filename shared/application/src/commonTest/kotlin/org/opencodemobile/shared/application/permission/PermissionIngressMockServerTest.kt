package org.opencodemobile.shared.application.permission

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.opencodemobile.shared.domain.cache.MutationGate
import org.opencodemobile.shared.domain.permission.BiometricAuthenticator
import org.opencodemobile.shared.domain.permission.BiometricResult
import org.opencodemobile.shared.domain.permission.NoOpPermissionNotifier
import org.opencodemobile.shared.domain.permission.PermissionDecision
import org.opencodemobile.shared.domain.permission.PermissionRequest
import org.opencodemobile.shared.domain.permission.PendingPermissionStore
import org.opencodemobile.shared.networking.permission.OpenCodePermissionGateway
import org.opencodemobile.shared.networking.realtime.NetworkingRealtimeTransport
import org.opencodemobile.shared.realtime.EventProcessor
import org.opencodemobile.shared.testsupport.MockOpenCodeScenario
import org.opencodemobile.shared.testsupport.MockOpenCodeServer
import org.opencodemobile.shared.testsupport.OpenCodeFixtures

/**
 * V1-06 ingress end to end (finding H2 of OPE-166): the **real** realtime pipeline
 * (`EventProcessor` + `NetworkingRealtimeTransport`) and the **real** gateway
 * (`OpenCodePermissionGateway`) drive the permission surface against
 * [MockOpenCodeServer].
 *
 * The app-shell composition root binds these pieces to a live profile; that
 * binding is tracked separately (the connection/onboarding root lands with the
 * L1 lot). Here every layer below the profile is the production one, so the
 * round trip is proven without the app shell:
 *
 * 1. `permission.asked` arrives through the stream and raises the surface,
 * 2. an approval needs the foreground state, the armed confirmation, and the
 *    biometric gate, and reaches the wire exactly once,
 * 3. restoring a persisted pending set across an app kill brings the surface
 *    back and never approves anything implicitly.
 *
 * These run on [Dispatchers.Default] so the mock's real inter-event delays are
 * honoured, exactly like [org.opencodemobile.shared.realtime.EventProcessorMockServerTest].
 */
@Suppress("InjectDispatcher") // Real-time mock tests on purpose; dispatchers are not wired into tests.
class PermissionIngressMockServerTest {

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

    /** Real-time wait so the mock's real inter-event delays are honoured. */
    private suspend fun awaitUntil(timeoutMillis: Long = 4_000L, condition: () -> Boolean) {
        withTimeout(timeoutMillis) {
            while (!condition()) delay(5L)
        }
    }

    /**
     * The whole graph under test, every piece production code: the event pipeline
     * is the [EventProcessor], the gateway implements both `PermissionPort` and
     * `PermissionEventDecoder`, and the bridge/coordinator are the application
     * layer.
     */
    private class Harness(
        val processor: EventProcessor,
        val store: RecordingPendingPermissionStore,
        val biometric: ScriptedBiometricAuthenticator,
        val coordinator: PermissionCoordinator,
        val bridge: PermissionRealtimeBridge,
    )

    private fun harness(
        server: MockOpenCodeServer,
        store: RecordingPendingPermissionStore = RecordingPendingPermissionStore(),
    ): Harness {
        val gateway = OpenCodePermissionGateway(
            httpClient = server.client,
            baseUrl = server.baseUrl,
        )
        val processor = EventProcessor(
            NetworkingRealtimeTransport(httpClient = server.client, baseUrl = server.baseUrl),
        )
        val biometric = ScriptedBiometricAuthenticator()
        val coordinator = PermissionCoordinator(
            port = gateway,
            store = store,
            mutationGate = AllowMutations,
            biometricAuthenticator = biometric,
            notifier = NoOpPermissionNotifier,
        )
        val bridge = PermissionRealtimeBridge(processor, gateway, coordinator)
        return Harness(processor, store, biometric, coordinator, bridge)
    }

    @Test
    fun permissionAskedThroughTheRealPipelineRaisesTheSurfaceAndPersistsIt() = runTest {
        val server = startServer(MockOpenCodeScenario.PermissionRequest)
        val scope = newScope()
        val harness = harness(server)
        val requestId = OpenCodeFixtures.PERMISSION_REQUEST_ID

        withContext(Dispatchers.Default) {
            harness.coordinator.start()
            harness.bridge.start(scope)
            harness.processor.start(scope)

            awaitUntil { harness.coordinator.state.value.request(requestId) != null }

            val state = harness.coordinator.state.value
            assertTrue(state.bannerVisible, "a pending permission must raise the banner")
            val request = state.request(requestId)!!
            assertEquals("bash", request.tool)
            assertEquals(listOf("rm -rf build"), request.patterns)
            assertTrue(
                request.rawArguments.contains("rm -rf build"),
                "the exact server arguments must be preserved: ${request.rawArguments}",
            )
            assertTrue(
                harness.store.persisted.any { it.id == requestId },
                "the pending request must be persisted so an app kill brings the banner back",
            )
        }
        harness.processor.stop()
    }

    @Test
    fun approvalNeedsForegroundArmingAndBiometricAndReachesTheWireOnce() = runTest {
        val server = startServer(MockOpenCodeScenario.PermissionRequest)
        val scope = newScope()
        val harness = harness(server)
        val requestId = OpenCodeFixtures.PERMISSION_REQUEST_ID

        withContext(Dispatchers.Default) {
            harness.coordinator.start()
            harness.bridge.start(scope)
            harness.processor.start(scope)

            awaitUntil { harness.coordinator.state.value.request(requestId) != null }
            val fingerprint = harness.coordinator.state.value.request(requestId)!!.contentFingerprint

            // Backgrounded: nothing may be approved.
            assertEquals(
                PermissionSubmitResult.NotForegrounded,
                harness.coordinator.approve(requestId, PermissionDecision.Once, fingerprint),
            )

            harness.coordinator.onForegroundChanged(true)
            // Foreground, but the confirmation screen has not armed the request.
            assertEquals(
                PermissionSubmitResult.NotArmed,
                harness.coordinator.approve(requestId, PermissionDecision.Once, fingerprint),
            )

            harness.coordinator.arm(requestId)
            // The screen must be bound to the exact content it rendered.
            assertEquals(
                PermissionSubmitResult.ContentChanged,
                harness.coordinator.approve(requestId, PermissionDecision.Once, "0000000000000000"),
            )
            assertEquals(
                0,
                harness.biometric.calls,
                "the biometric gate must not run before the foreground/content gates pass",
            )

            harness.biometric.result = BiometricResult.Failed("no match")
            assertEquals(
                PermissionSubmitResult.NotAuthenticated("no match"),
                harness.coordinator.approve(requestId, PermissionDecision.Once, fingerprint),
            )
            assertEquals(1, harness.biometric.calls, "the gate prompts once per attempt")

            assertEquals(
                emptyList(),
                server.permissionReplies,
                "a refused approval must not reach the wire",
            )

            harness.biometric.result = BiometricResult.Succeeded
            assertEquals(
                PermissionSubmitResult.Accepted,
                harness.coordinator.approve(requestId, PermissionDecision.Once, fingerprint),
            )
            assertEquals(2, harness.biometric.calls)
            assertEquals(listOf("once"), server.permissionReplies.map { it.reply })
            assertEquals(emptyList(), harness.coordinator.state.value.pending)
        }
        harness.processor.stop()
    }

    @Test
    fun restoreAfterAKillBringsTheSurfaceBackWithNoImplicitApproval() = runTest {
        val server = startServer(MockOpenCodeScenario.PermissionRequest)
        val requestId = OpenCodeFixtures.PERMISSION_REQUEST_ID
        val sharedStore = RecordingPendingPermissionStore()

        val firstScope = newScope()
        val first = harness(server, sharedStore)
        withContext(Dispatchers.Default) {
            first.coordinator.start()
            first.bridge.start(firstScope)
            first.processor.start(firstScope)
            awaitUntil { first.coordinator.state.value.request(requestId) != null }
        }

        // Kill: tear the pipeline and the scope down. The persisted set survives.
        first.processor.stop()
        firstScope.cancel()
        assertEquals(
            emptyList(),
            server.permissionReplies,
            "restoring a pending request must never approve it",
        )

        // Restart against the same persisted store.
        val secondScope = newScope()
        val second = harness(server, sharedStore)
        withContext(Dispatchers.Default) {
            second.coordinator.start()
            second.bridge.start(secondScope)
            second.processor.start(secondScope)
            awaitUntil { second.coordinator.state.value.request(requestId) != null }
            assertTrue(
                second.coordinator.state.value.bannerVisible,
                "a killed app must bring the banner back",
            )
        }
        second.processor.stop()
        assertEquals(
            emptyList(),
            server.permissionReplies,
            "the restored request is pending, not approved",
        )
    }
}

/**
 * Persists the pending set like the encrypted cache store: [saved] survives the
 * coordinator that owns it, so a "restart" reads it back.
 */
internal class RecordingPendingPermissionStore : PendingPermissionStore {
    private var saved: List<PermissionRequest> = emptyList()

    val persisted: List<PermissionRequest> get() = saved

    override suspend fun load(): List<PermissionRequest> = saved

    override suspend fun save(requests: List<PermissionRequest>) {
        saved = requests
    }
}

/** Online gate: the mock connection is always live. */
internal object AllowMutations : MutationGate {
    override fun mutationsAllowed(): Boolean = true
}

/** Biometric gate whose outcome a test controls; counts every prompt. */
internal class ScriptedBiometricAuthenticator(
    var result: BiometricResult = BiometricResult.Succeeded,
) : BiometricAuthenticator {
    var calls: Int = 0

    override suspend fun authenticate(reason: String): BiometricResult {
        calls += 1
        return result
    }
}
