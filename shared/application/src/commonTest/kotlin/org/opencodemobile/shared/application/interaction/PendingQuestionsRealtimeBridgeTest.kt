package org.opencodemobile.shared.application.interaction

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.opencodemobile.shared.domain.cache.ConnectionState
import org.opencodemobile.shared.domain.cache.ConnectivityMutationGate
import org.opencodemobile.shared.domain.event.ConnectionPhase
import org.opencodemobile.shared.domain.event.EventSource
import org.opencodemobile.shared.domain.event.RealtimeState
import org.opencodemobile.shared.domain.event.ServerEvent
import org.opencodemobile.shared.domain.interaction.PendingQuestion
import org.opencodemobile.shared.domain.interaction.QuestionItem
import org.opencodemobile.shared.domain.interaction.QuestionOption

private class FakeEventSource : EventSource {
    private val mutableState = MutableStateFlow(RealtimeState())
    private val mutableEvents = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 8)

    override val state: StateFlow<RealtimeState> = mutableState.asStateFlow()
    override val events: SharedFlow<ServerEvent> = mutableEvents.asSharedFlow()

    override fun start(scope: CoroutineScope) = Unit
    override fun stop() = Unit

    fun phase(phase: ConnectionPhase) {
        mutableState.value = mutableState.value.copy(phase = phase)
    }
}

/**
 * V1-07 reconnect cadence: the bridge reads `GET /question` once at start and
 * again on **every** transition into `Live`, never on the other phases.
 */
class PendingQuestionsRealtimeBridgeTest {

    private val question = PendingQuestion(
        id = "que_1",
        sessionId = "ses_1",
        questions = listOf(
            QuestionItem(
                question = "Which branch?",
                header = "Rebase target",
                options = listOf(QuestionOption("main"), QuestionOption("release")),
            ),
        ),
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun refreshRunsAtStartAndOnEveryReturnToLive() = runTest {
        val source = FakeEventSource()
        val gateway = FakeInteractionGateway(questions = listOf(question))
        val controller = PendingQuestionsController(
            gateway,
            ConnectivityMutationGate(ConnectionState.Online),
        )

        PendingQuestionsRealtimeBridge(source, controller).start(backgroundScope)
        runCurrent()

        assertEquals(1, gateway.pendingQuestionsReads, "the surface refreshes once at start")
        assertTrue(controller.state.value.hasPending, "the start refresh shows the question")

        // A first connection going Live refreshes again.
        source.phase(ConnectionPhase.Live)
        runCurrent()
        assertEquals(2, gateway.pendingQuestionsReads, "a return to Live re-reads the server")

        // Repeating Live without leaving it must not re-read (distinctUntilChanged).
        source.phase(ConnectionPhase.Live)
        runCurrent()
        assertEquals(2, gateway.pendingQuestionsReads)

        // Leave Live, come back: this is the "retour Live" the recette calls out.
        source.phase(ConnectionPhase.Polling)
        runCurrent()
        source.phase(ConnectionPhase.Live)
        runCurrent()
        assertEquals(3, gateway.pendingQuestionsReads, "each return to Live re-reads the server")
    }
}
