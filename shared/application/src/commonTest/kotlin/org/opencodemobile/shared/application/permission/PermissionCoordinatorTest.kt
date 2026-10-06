package org.opencodemobile.shared.application.permission

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.opencodemobile.shared.domain.cache.MutationGate
import org.opencodemobile.shared.domain.permission.BiometricAuthenticator
import org.opencodemobile.shared.domain.permission.BiometricResult
import org.opencodemobile.shared.domain.permission.PermissionCapabilities
import org.opencodemobile.shared.domain.permission.PermissionDecision
import org.opencodemobile.shared.domain.permission.PermissionEvent
import org.opencodemobile.shared.domain.permission.PermissionNotifier
import org.opencodemobile.shared.domain.permission.PermissionPort
import org.opencodemobile.shared.domain.permission.PermissionReplyOutcome
import org.opencodemobile.shared.domain.permission.PermissionRequest
import org.opencodemobile.shared.domain.permission.PendingPermissionStore

private class ToggleMutationGate(initialOnline: Boolean = true) : MutationGate {
    var online: Boolean = initialOnline
    override fun mutationsAllowed(): Boolean = online
}

private class InMemoryPendingPermissionStore(
    initial: List<PermissionRequest> = emptyList(),
) : PendingPermissionStore {
    var saved: List<PermissionRequest> = initial
        private set

    override suspend fun load(): List<PermissionRequest> = saved

    override suspend fun save(requests: List<PermissionRequest>): Unit {
        saved = requests
    }
}

private open class RecordingPermissionPort(
    var serverPending: List<PermissionRequest> = emptyList(),
    var outcome: PermissionReplyOutcome = PermissionReplyOutcome.Accepted,
) : PermissionPort {
    val replies: MutableList<Pair<String, PermissionDecision>> = mutableListOf()

    override suspend fun pendingPermissions(): List<PermissionRequest> = serverPending

    override suspend fun reply(
        requestId: String,
        decision: PermissionDecision,
    ): PermissionReplyOutcome {
        replies += requestId to decision
        return outcome
    }
}

/** A port whose reply can be held open, to model events arriving mid-round-trip. */
private class GatedPermissionPort : RecordingPermissionPort() {
    val gate: CompletableDeferred<PermissionReplyOutcome> = CompletableDeferred()

    override suspend fun reply(
        requestId: String,
        decision: PermissionDecision,
    ): PermissionReplyOutcome {
        replies += requestId to decision
        return gate.await()
    }
}

private class FakeBiometricAuthenticator(
    var result: BiometricResult = BiometricResult.Succeeded,
    var onAuthenticate: () -> Unit = {},
) : BiometricAuthenticator {
    var calls: Int = 0
    override suspend fun authenticate(reason: String): BiometricResult {
        calls += 1
        onAuthenticate()
        return result
    }
}

private class RecordingNotifier : PermissionNotifier {
    val snapshots: MutableList<List<String>> = mutableListOf()
    override suspend fun onPendingChanged(pending: List<PermissionRequest>) {
        snapshots += pending.map { it.id }
    }
}

class PermissionCoordinatorTest {

    private val bashRequest = PermissionRequest(
        id = "per_mock_0001",
        sessionId = "ses_mock_0001",
        tool = "bash",
        patterns = listOf("rm -rf build"),
        rawArguments = """{"command":"rm -rf build"}""",
        rememberScopes = listOf("bash:rm"),
        capabilities = PermissionCapabilities.fromServer(listOf("bash:rm")),
    )

    private fun coordinator(
        port: RecordingPermissionPort = RecordingPermissionPort(),
        store: InMemoryPendingPermissionStore = InMemoryPendingPermissionStore(),
        gate: ToggleMutationGate = ToggleMutationGate(),
        biometric: FakeBiometricAuthenticator = FakeBiometricAuthenticator(),
        notifier: RecordingNotifier = RecordingNotifier(),
    ): PermissionCoordinator = PermissionCoordinator(port, store, gate, biometric, notifier)

    @Test
    fun askedEventSurfacesTheBannerWithTheServerPayloadVerbatim() = runTest {
        val coordinator = coordinator()
        coordinator.onEvent(PermissionEvent.Asked(bashRequest))

        val state = coordinator.state.value
        assertTrue(state.bannerVisible, "a pending permission must make the banner visible")
        val active = state.activeRequest
        assertEquals("bash", active?.tool)
        assertEquals(listOf("rm -rf build"), active?.patterns)
        assertEquals("""{"command":"rm -rf build"}""", active?.rawArguments)
    }

    @Test
    fun approveRelaysTheExactDecisionOnceAfterTheBiometricGate() = runTest {
        val port = RecordingPermissionPort()
        val biometric = FakeBiometricAuthenticator()
        val coordinator = coordinator(port = port, biometric = biometric)
        coordinator.onForegroundChanged(true)
        coordinator.onEvent(PermissionEvent.Asked(bashRequest))
        coordinator.arm(bashRequest.id)

        val result = coordinator.submit(
            bashRequest.id,
            PermissionDecision.Once,
            bashRequest.contentFingerprint,
        )

        assertEquals(PermissionSubmitResult.Accepted, result)
        assertEquals(listOf(bashRequest.id to PermissionDecision.Once), port.replies)
        assertEquals(1, biometric.calls, "the biometric gate must run once per approval")
        assertTrue(coordinator.state.value.pending.isEmpty())
    }

    @Test
    fun denyNeedsNeitherTheConfirmationNorTheBiometricGate() = runTest {
        val port = RecordingPermissionPort()
        val biometric = FakeBiometricAuthenticator()
        val coordinator = coordinator(port = port, biometric = biometric)
        coordinator.onEvent(PermissionEvent.Asked(bashRequest))

        val result = coordinator.submit(
            bashRequest.id,
            PermissionDecision.Deny,
            displayedFingerprint = "ignored-for-deny",
        )

        assertEquals(PermissionSubmitResult.Accepted, result)
        assertEquals(listOf(bashRequest.id to PermissionDecision.Deny), port.replies)
        assertEquals(0, biometric.calls, "denying grants no capability and needs no biometric")
    }

    @Test
    fun approvalWithoutAForegroundConfirmationIsRefused() = runTest {
        val port = RecordingPermissionPort()
        val coordinator = coordinator(port = port)
        coordinator.onEvent(PermissionEvent.Asked(bashRequest))

        val result = coordinator.approve(
            bashRequest.id,
            PermissionDecision.Once,
            bashRequest.contentFingerprint,
        )

        assertEquals(PermissionSubmitResult.NotForegrounded, result)
        assertTrue(port.replies.isEmpty())
    }

    @Test
    fun approvalWithoutAnArmedConfirmationIsRefused() = runTest {
        val port = RecordingPermissionPort()
        val coordinator = coordinator(port = port)
        coordinator.onForegroundChanged(true)
        coordinator.onEvent(PermissionEvent.Asked(bashRequest))

        val result = coordinator.approve(
            bashRequest.id,
            PermissionDecision.Once,
            bashRequest.contentFingerprint,
        )

        assertEquals(PermissionSubmitResult.NotArmed, result)
        assertTrue(port.replies.isEmpty())
    }

    @Test
    fun aFailedBiometricGateNeverReachesTheWire() = runTest {
        val port = RecordingPermissionPort()
        val biometric = FakeBiometricAuthenticator(BiometricResult.Failed("no match"))
        val coordinator = coordinator(port = port, biometric = biometric)
        coordinator.onForegroundChanged(true)
        coordinator.onEvent(PermissionEvent.Asked(bashRequest))
        coordinator.arm(bashRequest.id)

        val result = coordinator.approve(
            bashRequest.id,
            PermissionDecision.Once,
            bashRequest.contentFingerprint,
        )

        assertEquals(PermissionSubmitResult.NotAuthenticated("no match"), result)
        assertTrue(port.replies.isEmpty(), "a failed gate must not authorize execution")
    }

    @Test
    fun decisionTheServerNeverExposedIsNeverRelayed() = runTest {
        val onceOnly = bashRequest.copy(capabilities = PermissionCapabilities.OnceOnly)
        val port = RecordingPermissionPort()
        val coordinator = coordinator(port = port)
        coordinator.onForegroundChanged(true)
        coordinator.onEvent(PermissionEvent.Asked(onceOnly))
        coordinator.arm(onceOnly.id)

        val result = coordinator.approve(
            onceOnly.id,
            PermissionDecision.Remember,
            onceOnly.contentFingerprint,
        )

        assertEquals(PermissionSubmitResult.Unavailable(PermissionDecision.Remember), result)
        assertTrue(port.replies.isEmpty(), "an unavailable decision must never reach the wire")

        val deny = coordinator.deny(onceOnly.id)
        assertEquals(PermissionSubmitResult.Unavailable(PermissionDecision.Deny), deny)
        assertTrue(port.replies.isEmpty())
    }

    @Test
    fun offlineMutationsAreRefusedAndNothingIsQueued() = runTest {
        val gate = ToggleMutationGate(initialOnline = false)
        val port = RecordingPermissionPort()
        val coordinator = coordinator(port = port, gate = gate)
        coordinator.onForegroundChanged(true)
        coordinator.onEvent(PermissionEvent.Asked(bashRequest))
        coordinator.arm(bashRequest.id)

        val result = coordinator.approve(
            bashRequest.id,
            PermissionDecision.Once,
            bashRequest.contentFingerprint,
        )

        assertEquals(PermissionSubmitResult.Offline, result)
        assertTrue(port.replies.isEmpty())
        assertTrue(coordinator.state.value.bannerVisible, "the request must stay pending while offline")
    }

    @Test
    fun killSurvivalRestoresThePendingRequestWithoutAnyImplicitApproval() = runTest {
        val store = InMemoryPendingPermissionStore(listOf(bashRequest))
        val port = RecordingPermissionPort()
        val restarted = PermissionCoordinator(
            port,
            store,
            ToggleMutationGate(initialOnline = false),
            FakeBiometricAuthenticator(),
            RecordingNotifier(),
        )

        restarted.start()

        assertTrue(restarted.state.value.bannerVisible, "a pending permission must survive an app kill")
        assertEquals(bashRequest.id, restarted.state.value.activeRequest?.id)
        assertTrue(port.replies.isEmpty(), "restoring must never approve implicitly")
    }

    @Test
    fun reconcileDropsRequestsDecidedWhileTheAppWasGone() = runTest {
        val store = InMemoryPendingPermissionStore(listOf(bashRequest))
        val port = RecordingPermissionPort(serverPending = emptyList())
        val coordinator = coordinator(port = port, store = store)

        coordinator.start()

        assertFalse(coordinator.state.value.bannerVisible, "the server is authoritative on reconnect")
        assertTrue(store.saved.isEmpty(), "the persisted set must match the reconciled server state")
    }

    @Test
    fun aRequestDecidedDuringTheReconcileRoundTripIsNotResurrected() = runTest {
        lateinit var coordinator: PermissionCoordinator
        val port = object : RecordingPermissionPort(serverPending = listOf(bashRequest)) {
            override suspend fun pendingPermissions(): List<PermissionRequest> {
                // The decision lands while the GET is in flight; the response was
                // captured before it and still lists the request as pending.
                coordinator.onEvent(PermissionEvent.Replied(bashRequest.id))
                return serverPending
            }
        }
        coordinator = coordinator(port = port)
        coordinator.onEvent(PermissionEvent.Asked(bashRequest))

        coordinator.reconcile()

        assertFalse(coordinator.state.value.bannerVisible, "a decided request must stay decided")
    }

    @Test
    fun aTransientlyOmittedPendingIdIsRestoredByTheNextReconcile() = runTest {
        val port = RecordingPermissionPort(serverPending = listOf(bashRequest))
        val coordinator = coordinator(port = port)
        coordinator.reconcile()
        assertTrue(coordinator.state.value.bannerVisible, "the server reported the request pending")

        // An SSE reconnect captured a momentarily empty (or truncated) response:
        // the banner drops and the omission is recorded, so an out-of-order event
        // cannot resurrect the id on its own.
        port.serverPending = emptyList()
        coordinator.reconcile()
        assertFalse(coordinator.state.value.bannerVisible)
        coordinator.onEvent(PermissionEvent.Asked(bashRequest))
        assertFalse(
            coordinator.state.value.bannerVisible,
            "the omission tombstone still suppresses a bare asked event",
        )

        // The next authoritative response reports the id pending again: the request
        // returns to the banner and the tombstone is dropped, so the omission is not
        // permanent (N1).
        port.serverPending = listOf(bashRequest)
        coordinator.reconcile()
        assertTrue(coordinator.state.value.bannerVisible, "a later reconcile must restore the request")
        assertEquals(bashRequest.id, coordinator.state.value.activeRequest?.id)
    }

    @Test
    fun leavingTheForegroundDuringTheBiometricPromptRefusesTheApproval() = runTest {
        val port = RecordingPermissionPort()
        lateinit var coordinator: PermissionCoordinator
        val biometric = FakeBiometricAuthenticator()
        // The activity is stopped while the biometric prompt is up.
        biometric.onAuthenticate = { coordinator.onForegroundChanged(false) }
        coordinator = coordinator(port = port, biometric = biometric)
        coordinator.onForegroundChanged(true)
        coordinator.onEvent(PermissionEvent.Asked(bashRequest))
        coordinator.arm(bashRequest.id)

        val result = coordinator.approve(
            bashRequest.id,
            PermissionDecision.Once,
            bashRequest.contentFingerprint,
        )

        assertEquals(PermissionSubmitResult.NotForegrounded, result)
        assertTrue(port.replies.isEmpty(), "a backgrounded approval must never reach the wire")
    }

    @Test
    fun disarmingDuringTheBiometricPromptRefusesTheApproval() = runTest {
        val port = RecordingPermissionPort()
        lateinit var coordinator: PermissionCoordinator
        val biometric = FakeBiometricAuthenticator()
        biometric.onAuthenticate = { coordinator.disarm() }
        coordinator = coordinator(port = port, biometric = biometric)
        coordinator.onForegroundChanged(true)
        coordinator.onEvent(PermissionEvent.Asked(bashRequest))
        coordinator.arm(bashRequest.id)

        val result = coordinator.approve(
            bashRequest.id,
            PermissionDecision.Once,
            bashRequest.contentFingerprint,
        )

        assertEquals(PermissionSubmitResult.NotArmed, result)
        assertTrue(port.replies.isEmpty(), "an unarmed approval must never reach the wire")
    }

    @Test
    fun leavingTheForegroundDisarmsTheConfirmation() = runTest {
        val port = RecordingPermissionPort()
        val coordinator = coordinator(port = port)
        coordinator.onForegroundChanged(true)
        coordinator.onEvent(PermissionEvent.Asked(bashRequest))
        coordinator.arm(bashRequest.id)

        coordinator.onForegroundChanged(false)
        val result = coordinator.approve(
            bashRequest.id,
            PermissionDecision.Once,
            bashRequest.contentFingerprint,
        )

        assertEquals(PermissionSubmitResult.NotForegrounded, result)
        assertTrue(port.replies.isEmpty())
        assertNull(coordinator.state.value.armedRequestId)
    }

    @Test
    fun contentChangeInvalidatesTheArmedConfirmation() = runTest {
        val port = RecordingPermissionPort()
        val coordinator = coordinator(port = port)
        coordinator.onForegroundChanged(true)
        coordinator.onEvent(PermissionEvent.Asked(bashRequest))
        coordinator.arm(bashRequest.id)
        val displayed = bashRequest.contentFingerprint

        port.serverPending = listOf(bashRequest.copy(rawArguments = """{"command":"rm -rf /"}"""))
        coordinator.reconcile()

        val result = coordinator.approve(bashRequest.id, PermissionDecision.Once, displayed)

        assertEquals(PermissionSubmitResult.ContentChanged, result)
        assertTrue(port.replies.isEmpty(), "a stale screen must never approve changed content")
    }

    @Test
    fun aReplayedAskedEventCannotResurrectADecidedRequest() = runTest {
        val port = RecordingPermissionPort()
        val coordinator = coordinator(port = port)
        coordinator.onForegroundChanged(true)
        coordinator.onEvent(PermissionEvent.Asked(bashRequest))
        coordinator.arm(bashRequest.id)
        coordinator.approve(bashRequest.id, PermissionDecision.Once, bashRequest.contentFingerprint)

        coordinator.onEvent(PermissionEvent.Asked(bashRequest))

        assertTrue(coordinator.state.value.pending.isEmpty())
        assertEquals(1, port.replies.size, "a replay must not trigger a second decision")
    }

    @Test
    fun anOutOfOrderRepliedThenAskedDoesNotResurrectADecidedRequest() = runTest {
        val coordinator = coordinator()

        // The server reports the decision before the event pipeline delivers the ask.
        coordinator.onEvent(PermissionEvent.Replied(bashRequest.id))
        coordinator.onEvent(PermissionEvent.Asked(bashRequest))

        assertTrue(
            coordinator.state.value.pending.isEmpty(),
            "an already-replied request must not be re-surfaced (T6)",
        )
    }

    @Test
    fun aRequestArrivingDuringTheReplyRoundTripIsNotLost() = runTest {
        val port = GatedPermissionPort()
        val coordinator = coordinator(port = port)
        coordinator.onForegroundChanged(true)
        coordinator.onEvent(PermissionEvent.Asked(bashRequest))
        coordinator.arm(bashRequest.id)

        val pendingSecond = bashRequest.copy(id = "per_mock_0002", patterns = listOf("git push"))
        val submit = async {
            coordinator.approve(bashRequest.id, PermissionDecision.Once, bashRequest.contentFingerprint)
        }

        // The reply is held open; a second request arrives meanwhile.
        coordinator.onEvent(PermissionEvent.Asked(pendingSecond))
        port.gate.complete(PermissionReplyOutcome.Accepted)
        submit.await()

        assertEquals(
            listOf("per_mock_0002"),
            coordinator.state.value.pending.map { it.id },
            "a request that arrived during the round trip must remain on the banner",
        )
    }

    @Test
    fun repliedEventRemovesTheRequestFromTheBanner() = runTest {
        val store = InMemoryPendingPermissionStore()
        val coordinator = coordinator(store = store)
        coordinator.onEvent(PermissionEvent.Asked(bashRequest))

        coordinator.onEvent(PermissionEvent.Replied(bashRequest.id))

        assertFalse(coordinator.state.value.bannerVisible)
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun everyPendingChangeIsSignalledToTheNotifier() = runTest {
        val notifier = RecordingNotifier()
        val coordinator = coordinator(notifier = notifier)

        coordinator.onEvent(PermissionEvent.Asked(bashRequest))
        coordinator.onEvent(PermissionEvent.Replied(bashRequest.id))

        assertEquals(listOf(listOf(bashRequest.id), emptyList()), notifier.snapshots)
    }
}
