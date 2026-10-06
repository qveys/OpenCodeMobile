package org.opencodemobile.shared.application.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.opencodemobile.shared.application.interaction.TurnAbortController
import org.opencodemobile.shared.domain.chat.ChatEventDecoder
import org.opencodemobile.shared.domain.event.ConnectionPhase
import org.opencodemobile.shared.domain.event.EventSource

/**
 * Bridges the realtime pipeline to [TranscriptController] (V1-05).
 *
 * - Every normalized SSE event is decoded by [decoder] and applied to the open
 *   transcript, so assistant text renders as it arrives.
 * - Every transition into [ConnectionPhase.Live] re-reads the authoritative
 *   transcript, so a reconnect (or an app restart) reconstitutes the transcript
 *   from the server with no duplicate and no lost text (D2).
 *
 * **V1-08 resynchronisation (abort).** After a successful abort the client holds
 * no event-derived turn state until the next authoritative snapshot wins
 * (`docs/ARCHITECTURE.md` §3.2, "the next snapshot wins"):
 *
 * - a `Live` transition is the pipeline's guarantee that a fresh server snapshot
 *   was just applied, so it is treated as authoritative:
 *   [TurnAbortController.onAuthoritativeSnapshot] clears the session's
 *   "awaiting resync" flag and the transcript is rebuilt from the server, and
 * - while a session is awaiting resync, an event-derived state for it is **not**
 *   painted ([TurnAbortController.acceptsEventDerivedState] is false), so a late
 *   event from the aborted turn can never become an orphan.
 *
 * It never sends a prompt; that is [ComposerController]'s single-shot job (D9).
 */
public class ChatRealtimeBridge(
    private val source: EventSource,
    private val decoder: ChatEventDecoder,
    private val transcripts: TranscriptController,
    private val turnAbort: TurnAbortController? = null,
) {
    /** Starts both collectors in [scope] and returns their job. */
    public fun start(scope: CoroutineScope): Job = scope.launch {
        launch {
            source.events.collect { event ->
                // The decoder contract is "never throws", but it is a third-party
                // seam: enforce it at the boundary so one bad payload cannot
                // permanently cancel this collector.
                val decoded = runCatching { decoder.decode(event.type, event.payload) }.getOrNull()
                    ?: return@collect
                // V1-08: never paint an event-derived state while the session is
                // awaiting the snapshot that wins after an abort.
                if (acceptsEventDerivedState()) transcripts.onEvent(decoded)
            }
        }
        launch {
            source.state
                .map { it.phase }
                .distinctUntilChanged()
                .collect { phase ->
                    if (phase == ConnectionPhase.Live) resyncFromTheAuthoritativeSnapshot()
                }
        }
    }

    /**
     * Whether an event-derived state for the open session may be painted. Returns
     * true when there is no abort surface, no open session, or no abort awaiting.
     */
    private fun acceptsEventDerivedState(): Boolean {
        val abort = turnAbort ?: return true
        val sessionId = transcripts.state.value.sessionId ?: return true
        return abort.acceptsEventDerivedState(sessionId)
    }

    /**
     * A `Live` transition always follows the pipeline applying a server snapshot
     * (`EventProcessor.runPipeline`), so it is the authoritative snapshot: tell
     * the abort surface the session may be trusted again, then rebuild the
     * transcript from the server.
     */
    private suspend fun resyncFromTheAuthoritativeSnapshot() {
        // The snapshot is authoritative: re-enable event-derived state only once
        // the server transcript has been rebuilt, otherwise a late event from the
        // aborted turn is painted during the refresh round-trip and then erased.
        val sessionId = transcripts.state.value.sessionId
        transcripts.refresh()
        turnAbort?.onAuthoritativeSnapshot(sessionId)
    }
}
