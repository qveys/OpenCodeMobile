package org.opencodemobile.android.connection

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.opencodemobile.android.cache.CacheConnection
import org.opencodemobile.android.chat.ChatConnection
import org.opencodemobile.android.permission.PermissionConnection
import org.opencodemobile.android.questions.QuestionConnection
import org.opencodemobile.android.session.SessionConnection
import org.opencodemobile.shared.domain.connection.ServerProfile
import org.opencodemobile.shared.domain.event.EventSource
import org.opencodemobile.shared.networking.adapter.OpenCodeV2Adapter
import org.opencodemobile.shared.realtime.EventProcessor

/**
 * The single project V1 opens (OP3): one server profile, one project root.
 *
 * It is the cache scope key (`CacheScope` / `SessionsScope`); V1 ships no project
 * picker, so every connection is bound to this stable id.
 */
public const val DEFAULT_PROJECT_ID: String = "default"

/**
 * The live server connection the L2/L3 surfaces are scoped to (OPE-176).
 *
 * It is built once per successful `connect` from the real
 * [OpenCodeV2Adapter]: the permission gateway and the realtime transport share
 * the adapter's pinned client and T1 credential gate, and one started
 * [EventProcessor] is the `source` every seam observes.
 *
 * The five seams are views over the same pieces, so the app shell binds them
 * together and no surface re-implements networking.
 */
public class LiveConnection(
    /** The real adapter: session, chat, interaction and event-decoding surface. */
    public val adapter: OpenCodeV2Adapter,
    /** The profile that was just verified and connected. */
    public val profile: ServerProfile,
    /** The process-wide realtime pipeline, started by [start]. */
    public val processor: EventProcessor,
    /** The single project scope (OP3). */
    public val projectId: String = DEFAULT_PROJECT_ID,
) {
    private val permissionGateway = adapter.permissionGateway(profile.baseUrl)

    /** V1-06: the real permission port + decoder over the adapter's client. */
    public val permission: PermissionConnection = object : PermissionConnection {
        override val port = permissionGateway
        override val decoder = permissionGateway
        override val source: EventSource = processor
        override val serverId: String = profile.id
    }

    /** V1-04: the adapter's session surface, scoped to the active profile. */
    public val session: SessionConnection = object : SessionConnection {
        override val gateway = adapter
        override val serverId: String = profile.id
        override val projectId: String = this@LiveConnection.projectId
    }

    /** V1-05/V1-07/V1-08/V1-09: transcript, interactions and event decoding. */
    public val chat: ChatConnection = object : ChatConnection {
        override val gateway = adapter
        override val interactions = adapter
        override val decoder = adapter
        override val source: EventSource = processor
        override val serverId: String = profile.id
        override val projectId: String = this@LiveConnection.projectId
    }

    /** V1-07: the adapter's pending-question surface. */
    public val questions: QuestionConnection = object : QuestionConnection {
        override val gateway = adapter
        override val source: EventSource = processor
        override val directory: String? = null
    }

    /** D8: the realtime pipeline the cache write path projects. */
    public val cache: CacheConnection = object : CacheConnection {
        override val source: EventSource = processor
        override val serverId: String = profile.id
        override val projectId: String = this@LiveConnection.projectId
    }

    /** Starts the realtime pipeline. Idempotent per [EventProcessor]. */
    public fun start(scope: CoroutineScope) {
        processor.start(scope)
    }

    /** Tears the realtime pipeline down (a killed/closed connection). */
    public fun stop() {
        processor.stop()
    }
}

/**
 * Holds the active [LiveConnection] as Koin state (OPE-176).
 *
 * The `Deferred*` seam resolvers read this at call time instead of resolving a
 * seam at graph-build time, so binding a connection after the app graph was
 * forced still reaches every surface. It is null while no connection is live, so
 * the seam resolvers keep their fail-closed `null` semantics (a placeholder
 * connection would freeze the realtime bridges on a no-op source).
 */
public class ConnectionBinder {
    private val mutableLive = MutableStateFlow<LiveConnection?>(null)

    /** Observable active connection; null before the first connect and after [clear]. */
    public val live: StateFlow<LiveConnection?> = mutableLive.asStateFlow()

    /** True while a connection is active. */
    public val connected: Boolean get() = mutableLive.value != null

    /** Binds [connection] (replacing any previous one, which is stopped first). */
    public fun bind(connection: LiveConnection) {
        mutableLive.value?.stop()
        mutableLive.value = connection
    }

    /** Stops and drops the active connection. */
    public fun clear() {
        mutableLive.value?.stop()
        mutableLive.value = null
    }

    public fun permission(): PermissionConnection? = mutableLive.value?.permission

    public fun session(): SessionConnection? = mutableLive.value?.session

    public fun chat(): ChatConnection? = mutableLive.value?.chat

    public fun questions(): QuestionConnection? = mutableLive.value?.questions

    public fun cache(): CacheConnection? = mutableLive.value?.cache
}
