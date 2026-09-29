package org.opencodemobile.shared.application.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
 * It never sends a prompt; that is [ComposerController]'s single-shot job (D9).
 */
public class ChatRealtimeBridge(
    private val source: EventSource,
    private val decoder: ChatEventDecoder,
    private val transcripts: TranscriptController,
) {
    /** Starts both collectors in [scope] and returns their job. */
    public fun start(scope: CoroutineScope): Job = scope.launch {
        launch {
            source.events.collect { event ->
                // The decoder contract is "never throws", but it is a third-party
                // seam: enforce it at the boundary so one bad payload cannot
                // permanently cancel this collector.
                val decoded = runCatching { decoder.decode(event.type, event.payload) }.getOrNull()
                decoded?.let(transcripts::onEvent)
            }
        }
        launch {
            source.state
                .map { it.phase }
                .distinctUntilChanged()
                .collect { phase ->
                    if (phase == ConnectionPhase.Live) transcripts.refresh()
                }
        }
    }
}
