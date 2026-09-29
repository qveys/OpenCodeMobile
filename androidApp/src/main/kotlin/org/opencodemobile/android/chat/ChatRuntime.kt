package org.opencodemobile.android.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.opencodemobile.shared.application.chat.ChatRealtimeBridge
import org.opencodemobile.shared.application.chat.TranscriptController
import org.opencodemobile.shared.application.interaction.TurnAbortController

/**
 * Starts the V1-05 realtime bridge once a connection is bound.
 *
 * It mirrors `PermissionRuntime`: the app shell has no connection composition
 * root yet, so [start] resolves the optional [ChatConnection] at call time and
 * returns null (inert) until one exists. The connection root calls [start] again
 * once it has bound one.
 *
 * [turnAbort] wires the V1-08 resynchronisation: the bridge gates event-derived
 * state after an abort and clears the wait on the next authoritative snapshot.
 */
public class ChatRuntime(
    private val transcripts: TranscriptController,
    private val turnAbort: TurnAbortController?,
    private val resolveConnection: () -> ChatConnection?,
) {
    /** Starts the bridge, or returns null while no connection is wired. */
    public fun start(scope: CoroutineScope): Job? {
        val connection = resolveConnection() ?: return null
        return ChatRealtimeBridge(
            source = connection.source,
            decoder = connection.decoder,
            transcripts = transcripts,
            turnAbort = turnAbort,
        ).start(scope)
    }
}
