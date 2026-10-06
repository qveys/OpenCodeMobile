package org.opencodemobile.shared.application.interaction

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.opencodemobile.shared.domain.cache.ConnectivityMutationGate
import org.opencodemobile.shared.domain.cache.ConnectionState

class TurnAbortControllerTest {

    @Test
    fun abortIsSentExactlyOnceAndWaitsForAnAuthoritativeSnapshot() = runTest {
        val gateway = FakeInteractionGateway()
        val controller = TurnAbortController(gateway, onlineGate())

        val outcome = controller.abort("ses_1")

        assertTrue(outcome.isSuccess)
        assertEquals(listOf("ses_1"), gateway.aborted, "the abort is sent once")
        assertTrue(controller.state.value.isAwaitingResync("ses_1"))
    }

    @Test
    fun eventDerivedStateIsRejectedUntilTheNextSnapshot() = runTest {
        val gateway = FakeInteractionGateway()
        val controller = TurnAbortController(gateway, onlineGate())
        assertTrue(controller.acceptsEventDerivedState("ses_1"))

        controller.abort("ses_1")

        // No orphan event from the aborted turn may be rendered before the
        // snapshot that wins (V1-08).
        assertFalse(controller.acceptsEventDerivedState("ses_1"))

        controller.onAuthoritativeSnapshot("ses_1")

        assertTrue(controller.acceptsEventDerivedState("ses_1"))
        assertFalse(controller.state.value.isAwaitingResync("ses_1"))
    }

    @Test
    fun abortCarriesTheActiveDirectory() = runTest {
        val gateway = FakeInteractionGateway()
        val controller = TurnAbortController(gateway, onlineGate())

        controller.abort("ses_1", directory = "/home/dev/workspace")

        assertEquals(listOf("ses_1"), gateway.aborted)
        assertEquals(
            "/home/dev/workspace",
            gateway.lastDirectory,
            "the abort must pass the active directory (ADR-0002 §3.3)",
        )
    }

    @Test
    fun abortIsRefusedOfflineAndNothingIsSent() = runTest {
        val gateway = FakeInteractionGateway()
        val controller = TurnAbortController(gateway, offlineGate())

        val outcome = controller.abort("ses_1")

        assertTrue(outcome.isFailure, "offline abort must fail (D8)")
        assertTrue(gateway.aborted.isEmpty(), "nothing may be sent offline")
        assertFalse(controller.state.value.isAwaitingResync("ses_1"))
    }

    @Test
    fun aFailedAbortIsNotRetriedAutomatically() = runTest {
        val gateway = FakeInteractionGateway().apply { abortError = IllegalStateException("abort rejected") }
        val controller = TurnAbortController(gateway, onlineGate())

        val outcome = controller.abort("ses_1")

        assertTrue(outcome.isFailure)
        assertTrue(gateway.aborted.isEmpty())
        assertEquals("abort rejected", controller.state.value.error)
        assertFalse(controller.state.value.isAwaitingResync("ses_1"))
    }

    private fun onlineGate() = ConnectivityMutationGate(ConnectionState.Online)

    private fun offlineGate() = ConnectivityMutationGate(ConnectionState.Offline)
}
