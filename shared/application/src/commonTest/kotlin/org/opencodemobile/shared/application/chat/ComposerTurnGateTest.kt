package org.opencodemobile.shared.application.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.opencodemobile.shared.domain.cache.ConnectionState
import org.opencodemobile.shared.domain.cache.ConnectivityMutationGate
import org.opencodemobile.shared.domain.chat.ChatPrompt
import org.opencodemobile.shared.domain.chat.OpenCodeChatGateway
import org.opencodemobile.shared.domain.chat.TranscriptMessage

private class RecordingChatGateway : OpenCodeChatGateway {
    val sent: MutableList<String> = mutableListOf()

    override suspend fun transcript(sessionId: String): List<TranscriptMessage> = emptyList()

    override suspend fun sendPrompt(sessionId: String, prompt: ChatPrompt) {
        sent += prompt.text
    }
}

/**
 * V1-07 turn gate: while the pending-question surface reports `blocksTurn`, the
 * composer refuses a prompt before the wire. The UI already disables the
 * affordance; this is the fail-closed backstop behind it.
 */
class ComposerTurnGateTest {

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun aPendingQuestionRefusesTheSendBeforeTheWire() = runTest {
        val gateway = RecordingChatGateway()
        var blocked = true
        val controller = ComposerController(
            gateway = gateway,
            drafts = InMemoryComposerDraftStore(),
            mutationGate = ConnectivityMutationGate(ConnectionState.Online),
            turnBlocked = { blocked },
        )

        controller.open("ses_1")
        controller.updateDraft("Explain the D9 rule")

        val refused = controller.send()

        assertTrue(refused.isFailure, "a question blocks the turn (V1-07)")
        assertTrue(gateway.sent.isEmpty(), "nothing may reach the wire while blocked")

        // The question is answered: the same draft may now be sent.
        blocked = false
        assertTrue(controller.send().isSuccess)
        assertEquals(listOf("Explain the D9 rule"), gateway.sent)
    }
}
