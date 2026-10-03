package org.opencodemobile.android.connection

import kotlinx.coroutines.CoroutineScope
import org.opencodemobile.shared.domain.connection.ServerProfile
import org.opencodemobile.shared.networking.adapter.OpenCodeV2Adapter
import org.opencodemobile.shared.realtime.EventProcessor

/**
 * Binds the live connection into the app shell (OPE-176).
 *
 * Called from `ConnectionSetupController.onConnected` once the handshake
 * succeeded: it builds the [LiveConnection] over the already-verified
 * [adapter], starts the realtime pipeline, publishes it through the
 * [ConnectionBinder], and lets [onBound] restart every surface whose realtime
 * bridge was inert until now (permissions, questions, chat, cache write path).
 *
 * It never approves or sends anything; it only makes the verified connection
 * reachable to the `Deferred*` seams.
 */
public class ConnectionBindingController(
    private val binder: ConnectionBinder,
    private val adapter: OpenCodeV2Adapter,
    private val processorScope: CoroutineScope,
    private val onBound: (LiveConnection) -> Unit = {},
) {
    /** Publishes [profile] as the active connection and starts the pipeline. */
    public fun onConnected(profile: ServerProfile) {
        val connection = LiveConnection(
            adapter = adapter,
            profile = profile,
            processor = EventProcessor(adapter.eventTransport(profile.baseUrl)),
        )
        connection.start(processorScope)
        binder.bind(connection)
        onBound(connection)
    }

    /** Drops the active connection (disconnect / profile removed). */
    public fun onDisconnected() {
        binder.clear()
    }
}
