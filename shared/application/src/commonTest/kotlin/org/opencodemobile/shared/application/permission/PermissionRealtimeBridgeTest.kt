package org.opencodemobile.shared.application.permission

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.opencodemobile.shared.domain.cache.MutationGate
import org.opencodemobile.shared.domain.event.ConnectionPhase
import org.opencodemobile.shared.domain.event.EventSource
import org.opencodemobile.shared.domain.event.RealtimeState
import org.opencodemobile.shared.domain.event.ServerEvent
import org.opencodemobile.shared.domain.permission.PermissionCapabilities
import org.opencodemobile.shared.domain.permission.PermissionEvent
import org.opencodemobile.shared.domain.permission.PermissionEventDecoder
import org.opencodemobile.shared.domain.permission.PermissionPort
import org.opencodemobile.shared.domain.permission.PermissionReplyOutcome
import org.opencodemobile.shared.domain.permission.PermissionRequest
import org.opencodemobile.shared.domain.permission.PendingPermissionStore

private class FakeEventSource : EventSource {
    private val mutableState = MutableStateFlow(RealtimeState())
    private val mutableEvents = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 8)

    override val state: StateFlow<RealtimeState> = mutableState.asStateFlow()
    override val events: SharedFlow<ServerEvent> = mutableEvents.asSharedFlow()

    override fun start(scope: CoroutineScope) = Unit
    override fun stop() = Unit

    fun emit(event: ServerEvent): Boolean = mutableEvents.tryEmit(event)
    fun phase(phase: ConnectionPhase) {
        mutableState.value = mutableState.value.copy(phase = phase)
    }
}

private class SingleEventDecoder(private val request: PermissionRequest) : PermissionEventDecoder {
    override fun decode(type: String, payload: String): PermissionEvent? =
        if (type == "permission.asked") PermissionEvent.Asked(request) else null
}

class PermissionRealtimeBridgeTest {

    private val request = PermissionRequest(
        id = "per_1",
        sessionId = "ses_1",
        tool = "bash",
        patterns = listOf("rm -rf build"),
        rawArguments = """{"command":"rm -rf build"}""",
        rememberScopes = emptyList(),
        capabilities = PermissionCapabilities.fromServer(emptyList()),
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun decodedEventsReachTheCoordinatorAndLiveReconciles() = runTest {
        val source = FakeEventSource()
        val port = object : PermissionPort {
            var serverPending: List<PermissionRequest> = emptyList()
            override suspend fun pendingPermissions(): List<PermissionRequest> = serverPending
            override suspend fun reply(
                requestId: String,
                decision: org.opencodemobile.shared.domain.permission.PermissionDecision,
            ): PermissionReplyOutcome = PermissionReplyOutcome.Accepted
        }
        val coordinator = PermissionCoordinator(
            port = port,
            store = object : PendingPermissionStore {
                override suspend fun load(): List<PermissionRequest> = emptyList()
                override suspend fun save(requests: List<PermissionRequest>) = Unit
            },
            mutationGate = object : MutationGate {
                override fun mutationsAllowed(): Boolean = true
            },
        )
        val bridge = PermissionRealtimeBridge(source, SingleEventDecoder(request), coordinator)
        bridge.start(backgroundScope)
        runCurrent() // let both collectors subscribe before emitting

        source.emit(ServerEvent(sequence = 1, id = "evt_1", type = "permission.asked", payload = "{}"))
        runCurrent()
        assertEquals(listOf("per_1"), coordinator.state.value.pending.map { it.id })

        // A reconnect to Live reconciles against the authoritative server list.
        port.serverPending = emptyList()
        source.phase(ConnectionPhase.Live)
        runCurrent()
        assertEquals(emptyList(), coordinator.state.value.pending.map { it.id })
    }
}
