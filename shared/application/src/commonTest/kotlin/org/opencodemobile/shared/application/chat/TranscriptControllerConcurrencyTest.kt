package org.opencodemobile.shared.application.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.opencodemobile.shared.domain.chat.ChatEvent
import org.opencodemobile.shared.domain.chat.ChatPrompt
import org.opencodemobile.shared.domain.chat.OpenCodeChatGateway
import org.opencodemobile.shared.domain.chat.TranscriptMessage
import org.opencodemobile.shared.domain.chat.TranscriptPart
import org.opencodemobile.shared.domain.chat.TranscriptRole

/**
 * Regression tests for the V1-05 mutation concurrency finding (OPE-280): the
 * realtime collector and [TranscriptController.refresh] can run on different
 * coroutine contexts, so a snapshot that lands after an event must not drop the
 * event's text (the KDoc "no lost text"), and no mutation may duplicate a
 * message id (which would produce duplicate `LazyColumn` keys).
 */
class TranscriptControllerConcurrencyTest {

    private val sessionId = "ses_concurrency"

    /**
     * A `refresh` is in flight (the gateway is suspended) when an event arrives.
     * The stale snapshot must be merged underneath the event instead of replacing
     * the live view, so the streamed text survives.
     */
    @Test
    fun anEventAppliedDuringRefreshIsNotLost() = runTest {
        val released = CompletableDeferred<Unit>()
        val gateway = SuspendingChatGateway(released)
        val controller = TranscriptController(gateway)

        controller.open(sessionId)
        assertEquals(listOf("msg_1"), controller.state.value.messages.map { it.id })

        val refresher = launch { controller.refresh() }
        while (gateway.calls < 2) yield()
        controller.onEvent(
            ChatEvent.PartUpdated(
                messageId = "msg_2",
                sessionId = sessionId,
                part = TranscriptPart("prt_2", "text", "streamed during refresh"),
            ),
        )
        released.complete(Unit)
        refresher.join()

        val ids = controller.state.value.messages.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "a concurrent refresh must not duplicate an id: $ids")
        assertTrue("msg_1" in ids, "the server snapshot must still be applied: $ids")
        assertTrue("msg_2" in ids, "an event applied during the round-trip must not be dropped: $ids")
        assertEquals(
            "streamed during refresh",
            controller.state.value.messages.first { it.id == "msg_2" }.text,
        )
    }

    private class SuspendingChatGateway(
        private val releaseSecondCall: CompletableDeferred<Unit>,
    ) : OpenCodeChatGateway {
        var calls: Int = 0
            private set

        override suspend fun transcript(sessionId: String): List<TranscriptMessage> {
            calls += 1
            if (calls >= 2) releaseSecondCall.await()
            return listOf(
                TranscriptMessage(
                    id = "msg_1",
                    sessionId = sessionId,
                    role = TranscriptRole.Assistant,
                    parts = listOf(TranscriptPart("prt_1", "text", "hello")),
                ),
            )
        }

        override suspend fun sendPrompt(sessionId: String, prompt: ChatPrompt): Unit = Unit
    }
}
