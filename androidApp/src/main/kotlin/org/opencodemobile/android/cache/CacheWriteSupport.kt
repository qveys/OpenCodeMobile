package org.opencodemobile.android.cache

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.opencodemobile.shared.application.cache.CacheScope
import org.opencodemobile.shared.application.cache.CacheWritePipeline
import org.opencodemobile.shared.data.cache.CacheStack
import org.opencodemobile.shared.domain.event.EventSource

/**
 * The active server connection the D8 cache write path is scoped to (OPE-180).
 *
 * The app shell has no connection/onboarding composition root yet, so this seam
 * lets the cache write path be assembled today against an **optional**
 * connection: the connection composition root binds a `CacheConnection` once it
 * exists, and [CacheWriteRuntime] stays inert until then. It mirrors the
 * `PermissionConnection` seam in `org.opencodemobile.android.permission`, and
 * both should be unified when the connection root lands.
 *
 * `source` is the started `EventProcessor`; the stack's gated writer is the only
 * write surface, so this seam never carries a mutation path of its own.
 */
public interface CacheConnection {
    /** The per-connection realtime pipeline the write path observes. */
    public val source: EventSource

    /** The server profile the cache is scoped to. */
    public val serverId: String

    /** The single project V1 opens, per OP3. */
    public val projectId: String
}

/**
 * Starts the D8 cache write path once a connection is wired (OPE-180).
 *
 * The resolved [CacheWritePipeline] binds the connection's [EventSource] to
 * `CacheStack.gate` and projects server snapshots/events through
 * `CacheStack.writer()`. Resolution is lazy and re-entrant: a connection
 * composition root that registers `CacheConnection` after the application graph
 * was first resolved can still start the write path by calling [start] again.
 */
public class CacheWriteRuntime(
    private val scope: CoroutineScope,
    private val resolveConnection: () -> CacheConnection?,
    private val resolveStack: () -> CacheStack?,
) {
    private var job: Job? = null

    /** No-op while no connection or stack is wired; safe to call more than once. */
    public fun start() {
        if (job != null) return
        val connection = resolveConnection() ?: return
        val stack = resolveStack() ?: return
        job = scope.launch {
            CacheWritePipeline(
                source = connection.source,
                writer = stack.writer(),
                gate = stack.gate,
                scope = CacheScope(
                    serverId = connection.serverId,
                    projectId = connection.projectId,
                ),
            ).start(this)
        }
    }
}
