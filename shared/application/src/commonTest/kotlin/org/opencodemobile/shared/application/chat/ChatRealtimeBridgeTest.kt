package org.opencodemobile.shared.application.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.opencodemobile.shared.application.interaction.FakeInteractionGateway
import org.opencodemobile.shared.application.interaction.TurnAbortController
import org.opencodemobile.shared.domain.cache.MutationGate
import org.opencodemobile.shared.domain.chat.ChatEvent
import org.opencodemobile.shared.domain.chat.ChatEventDecoder
import org.opencodemobile.shared.domain.chat.ChatPrompt
import org.opencodemobile.shared.domain.chat.OpenCodeChatGateway
import org.opencodemobile.shared.domain.chat.TranscriptMessage
import org.opencodemobile.shared.domain.chat.TranscriptPart
import org.opencodemobile.shared.domain.chat.TranscriptRole
import org.opencodemobile.shared.domain.event.ConnectionPhase
import org.opencodemobile.shared.domain.event.EventSource
import org.opencodemobile.shared.domain.event.RealtimeState
import org.opencodemobile.shared.domain.event.ServerEvent

/**
 * The V1-08 seam of [ChatRealtimeBridge], deterministically: the render consults
 * [TurnAbortController.acceptsEventDerivedState] before painting an event-derived
 * state, and a return to `Live` (the authoritative snapshot) clears the wait and
 * rebuilds the transcript from the server.
 *
 * The end-to-end proof against `MockOpenCodeServer` lives in
 * [ChatTranscriptMockServerTest]; this pins the exact seam with a controllable
 * source so the two halves of the wiring cannot silently regress.
 */
class ChatRealtimeBridgeTest {

    private val sessionId = "ses_1"

    @Test
    fun anEventAfterAnAbortIsNotPaintedUntilTheAuthoritativeSnapshot() = runTest(UnconfinedTestDispatcher()) {
        val source = FakeEventSource()
        val gateway = BridgeChatGateway()
        val decoder = ScopedDecoder()
        val transcripts = TranscriptController(gateway)
        val turnAbort = TurnAbortController(FakeInteractionGateway(), AlwaysOnline)
        val bridge = ChatRealtimeBridge(source, decoder, transcripts, turnAbort)
        bridge.start(backgroundScope)
        transcripts.open(sessionId)
        testScheduler.advanceUntilIdle()

        assertTrue(turnAbort.acceptsEventDerivedState(sessionId))
        assertTrue(transcripts.state.value.messages.isEmpty())

        // A normal event is painted.
        decoder.messageId = "msg_live"
        source.emit(payload = "live text")
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("msg_live"), transcripts.state.value.messages.map { it.id })

        // The user aborts: event-derived state is refused from here.
        assertTrue(turnAbort.abort(sessionId).isSuccess)
        testScheduler.advanceUntilIdle()
        assertFalse(turnAbort.acceptsEventDerivedState(sessionId))

        // A late event from the aborted turn must not be painted.
        decoder.messageId = "msg_orphan"
        source.emit(payload = "orphan text")
        testScheduler.advanceUntilIdle()
        assertTrue(
            transcripts.state.value.messages.none { it.id == "msg_orphan" },
            "an orphan event from the aborted turn must not render: ${transcripts.state.value.messages}",
        )

        // The next authoritative snapshot wins: the session is trusted again and
        // the transcript is rebuilt from the server.
        gateway.messages = listOf(serverMessage("msg_server"))
        source.advanceTo(ConnectionPhase.Live)
        testScheduler.advanceUntilIdle()

        assertTrue(turnAbort.acceptsEventDerivedState(sessionId))
        assertEquals(
            listOf("msg_server"),
            transcripts.state.value.messages.map { it.id },
            "the snapshot must replace the event-derived state",
        )
    }

    private fun serverMessage(id: String) = TranscriptMessage(
        id = id,
        sessionId = sessionId,
        role = TranscriptRole.Assistant,
        parts = listOf(TranscriptPart("prt_$id", "text", "from the server")),
    )
}

private object AlwaysOnline : MutationGate {
    override fun mutationsAllowed(): Boolean = true
}

/** A controllable [EventSource] for the bridge seam. */
private class FakeEventSource : EventSource {
    // `replay` so the collector receives an event emitted before it subscribed,
    // which keeps the test deterministic on the test scheduler.
    private val mutableEvents = MutableSharedFlow<ServerEvent>(replay = 16)
    private val mutableState = MutableStateFlow(RealtimeState())

    override val events: SharedFlow<ServerEvent> = mutableEvents
    override val state: StateFlow<RealtimeState> = mutableState

    fun emit(payload: String) {
        mutableEvents.tryEmit(ServerEvent(sequence = 1L, id = null, type = "message.part.updated", payload = payload))
    }

    fun advanceTo(phase: ConnectionPhase) {
        mutableState.value = mutableState.value.copy(phase = phase)
    }

    override fun start(scope: kotlinx.coroutines.CoroutineScope): Unit = Unit
    override fun stop(): Unit = Unit
}

/** A decoder whose next [messageId] is set by the test. */
private class ScopedDecoder : ChatEventDecoder {
    var messageId: String = "msg_live"

    override fun decode(type: String, payload: String): ChatEvent = ChatEvent.PartUpdated(
        messageId = messageId,
        sessionId = "ses_1",
        part = TranscriptPart("prt_$messageId", "text", payload),
    )
}

private class BridgeChatGateway(
    var messages: List<TranscriptMessage> = emptyList(),
) : OpenCodeChatGateway {
    override suspend fun transcript(sessionId: String): List<TranscriptMessage> = messages

    override suspend fun sendPrompt(sessionId: String, prompt: ChatPrompt): Unit = Unit
}
