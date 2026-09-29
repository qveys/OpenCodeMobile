package org.opencodemobile.shared.application.permission

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.opencodemobile.shared.domain.cache.MutationGate
import org.opencodemobile.shared.domain.permission.PermissionCapabilities
import org.opencodemobile.shared.domain.permission.PermissionDecision
import org.opencodemobile.shared.domain.permission.PermissionEvent
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

private class RecordingPermissionPort(
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

    private val armedConfirmation = PermissionConfirmation(foregrounded = true, authenticated = true)

    private fun coordinator(
        port: RecordingPermissionPort = RecordingPermissionPort(),
        store: InMemoryPendingPermissionStore = InMemoryPendingPermissionStore(),
        gate: ToggleMutationGate = ToggleMutationGate(),
    ): Triple<PermissionCoordinator, RecordingPermissionPort, InMemoryPendingPermissionStore> =
        Triple(PermissionCoordinator(port, store, gate), port, store)

    @Test
    fun askedEventSurfacesTheBannerWithTheServerPayloadVerbatim() = runTest {
        val (coordinator, _, _) = coordinator()
        coordinator.onEvent(PermissionEvent.Asked(bashRequest))

        val state = coordinator.state.value
        assertTrue(state.bannerVisible, "a pending permission must make the banner visible")
        val active = state.activeRequest
        assertEquals("bash", active?.tool)
        assertEquals(listOf("rm -rf build"), active?.patterns)
        assertEquals("""{"command":"rm -rf build"}""", active?.rawArguments)
    }

    @Test
    fun submitRelaysTheExactDecisionOnce() = runTest {
        val (coordinator, port, _) = coordinator()
        coordinator.onForegroundChanged(true)
        coordinator.onEvent(PermissionEvent.Asked(bashRequest))
        coordinator.arm(bashRequest.id)

        val result = coordinator.submit(bashRequest.id, PermissionDecision.Once, armedConfirmation)

        assertEquals(PermissionSubmitResult.Accepted, result)
        assertEquals(listOf(bashRequest.id to PermissionDecision.Once), port.replies)
        assertTrue(coordinator.state.value.pending.isEmpty(), "the decided request must leave the banner")
    }

    @Test
    fun decisionTheServerNeverExposedIsNeverRelayed() = runTest {
        val onceOnly = bashRequest.copy(capabilities = PermissionCapabilities.OnceOnly)
        val (coordinator, port, _) = coordinator()
        coordinator.onForegroundChanged(true)
        coordinator.onEvent(PermissionEvent.Asked(onceOnly))
        coordinator.arm(onceOnly.id)

        val result = coordinator.submit(onceOnly.id, PermissionDecision.Remember, armedConfirmation)

        assertEquals(
            PermissionSubmitResult.Unavailable(PermissionDecision.Remember),
            result,
        )
        assertTrue(port.replies.isEmpty(), "an unavailable decision must never reach the wire")
    }

    @Test
    fun offlineMutationsAreRefusedAndNothingIsQueued() = runTest {
        val gate = ToggleMutationGate(initialOnline = false)
        val (coordinator, port, _) = coordinator(gate = gate)
        coordinator.onForegroundChanged(true)
        coordinator.onEvent(PermissionEvent.Asked(bashRequest))
        coordinator.arm(bashRequest.id)

        val result = coordinator.submit(bashRequest.id, PermissionDecision.Once, armedConfirmation)

        assertEquals(PermissionSubmitResult.Offline, result)
        assertTrue(port.replies.isEmpty())
        assertTrue(coordinator.state.value.bannerVisible, "the request must stay pending while offline")
    }

    @Test
    fun killSurvivalRestoresThePendingRequestWithoutAnyImplicitApproval() = runTest {
        val store = InMemoryPendingPermissionStore(listOf(bashRequest))
        val gate = ToggleMutationGate(initialOnline = false)
        val port = RecordingPermissionPort()
        val restarted = PermissionCoordinator(port, store, gate)

        restarted.start()

        assertTrue(restarted.state.value.bannerVisible, "a pending permission must survive an app kill")
        assertEquals(bashRequest.id, restarted.state.value.activeRequest?.id)
        assertTrue(
            port.replies.isEmpty(),
            "restoring a pending request must never approve it implicitly",
        )
    }

    @Test
    fun reconcileDropsRequestsDecidedWhileTheAppWasGone() = runTest {
        val store = InMemoryPendingPermissionStore(listOf(bashRequest))
        val port = RecordingPermissionPort(serverPending = emptyList())
        val coordinator = PermissionCoordinator(port, store, ToggleMutationGate())

        coordinator.start()

        assertFalse(coordinator.state.value.bannerVisible, "the server is authoritative on reconnect")
        assertTrue(store.saved.isEmpty(), "the persisted set must match the reconciled server state")
    }

    @Test
    fun leavingTheForegroundDisarmsTheConfirmation() = runTest {
        val (coordinator, port, _) = coordinator()
        coordinator.onForegroundChanged(true)
        coordinator.onEvent(PermissionEvent.Asked(bashRequest))
        coordinator.arm(bashRequest.id)

        coordinator.onForegroundChanged(false)
        val result = coordinator.submit(bashRequest.id, PermissionDecision.Once, armedConfirmation)

        assertEquals(PermissionSubmitResult.NotArmed, result)
        assertTrue(port.replies.isEmpty(), "a tap landing after a background transition must not submit")
        assertNull(coordinator.state.value.armedRequestId)
    }

    @Test
    fun contentChangeInvalidatesTheArmedConfirmation() = runTest {
        val (coordinator, port, _) = coordinator()
        coordinator.onForegroundChanged(true)
        coordinator.onEvent(PermissionEvent.Asked(bashRequest))
        coordinator.arm(bashRequest.id)

        port.serverPending = listOf(bashRequest.copy(rawArguments = """{"command":"rm -rf /"}"""))
        coordinator.reconcile()

        val result = coordinator.submit(bashRequest.id, PermissionDecision.Once, armedConfirmation)

        assertEquals(PermissionSubmitResult.ContentChanged, result)
        assertTrue(port.replies.isEmpty(), "a stale screen must never approve changed content")
    }

    @Test
    fun approvingRequiresAuthenticationButDenyingDoesNot() = runTest {
        val withoutAuthentication = PermissionConfirmation(foregrounded = true, authenticated = false)

        val (approvalCoordinator, approvalPort, _) = coordinator()
        approvalCoordinator.onForegroundChanged(true)
        approvalCoordinator.onEvent(PermissionEvent.Asked(bashRequest))
        approvalCoordinator.arm(bashRequest.id)
        assertEquals(
            PermissionSubmitResult.NotAuthenticated,
            approvalCoordinator.submit(bashRequest.id, PermissionDecision.Once, withoutAuthentication),
        )
        assertTrue(approvalPort.replies.isEmpty())

        val (denyCoordinator, denyPort, _) = coordinator()
        denyCoordinator.onForegroundChanged(true)
        denyCoordinator.onEvent(PermissionEvent.Asked(bashRequest))
        denyCoordinator.arm(bashRequest.id)
        assertEquals(
            PermissionSubmitResult.Accepted,
            denyCoordinator.submit(bashRequest.id, PermissionDecision.Deny, withoutAuthentication),
        )
        assertEquals(listOf(bashRequest.id to PermissionDecision.Deny), denyPort.replies)
    }

    @Test
    fun aReplayedAskedEventCannotResurrectADecidedRequest() = runTest {
        val (coordinator, port, _) = coordinator()
        coordinator.onForegroundChanged(true)
        coordinator.onEvent(PermissionEvent.Asked(bashRequest))
        coordinator.arm(bashRequest.id)
        coordinator.submit(bashRequest.id, PermissionDecision.Once, armedConfirmation)

        coordinator.onEvent(PermissionEvent.Asked(bashRequest))

        assertTrue(coordinator.state.value.pending.isEmpty())
        assertEquals(1, port.replies.size, "a replay must not trigger a second decision")
    }

    @Test
    fun repliedEventRemovesTheRequestFromTheBanner() = runTest {
        val (coordinator, _, store) = coordinator()
        coordinator.onEvent(PermissionEvent.Asked(bashRequest))

        coordinator.onEvent(PermissionEvent.Replied(bashRequest.id))

        assertFalse(coordinator.state.value.bannerVisible)
        assertTrue(store.saved.isEmpty())
    }
}
