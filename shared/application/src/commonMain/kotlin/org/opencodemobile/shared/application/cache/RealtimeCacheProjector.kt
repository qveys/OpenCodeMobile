package org.opencodemobile.shared.application.cache

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.opencodemobile.shared.domain.cache.CacheMutationNotAllowedException
import org.opencodemobile.shared.domain.cache.CachedSession
import org.opencodemobile.shared.domain.cache.CachedSyncMetadata
import org.opencodemobile.shared.domain.cache.SessionCacheWriter
import org.opencodemobile.shared.domain.event.EventSource
import org.opencodemobile.shared.domain.event.RealtimeSnapshot
import org.opencodemobile.shared.domain.event.RealtimeState
import org.opencodemobile.shared.domain.event.ServerEvent

/**
 * The `ServerId -> ProjectId` scope one projector writes under (D8 / OP3).
 *
 * V1 stores a single profile and opens one project at a time, so the scope is
 * fixed when the projector is built. Keeping it explicit means multi-profile
 * stays a composition change, not a rewrite.
 */
public data class CacheScope(
    public val serverId: String,
    public val projectId: String,
)

/**
 * The production consumer of [SessionCacheWriter] (OPE-172, item 3).
 *
 * It is the bridge the security review asked for: the cache is written **only**
 * from the realtime pipeline (`shared/realtime`, OPE-106) — never from a user
 * action. Concretely:
 *
 * - every reconciled server snapshot (D2) upserts its sessions, and
 * - every normalized server event advances the project-level sync cursor.
 *
 * The writer it receives is the D8-gated one, so while the app is offline every
 * attempt is refused and swallowed: offline is read-only, the cache is
 * disposable, and the next snapshot rebuilds it. Nothing is queued.
 *
 * A wiring fault in one sink must not stop the pipeline, so a refused write is
 * the only failure treated as benign; everything else propagates to the caller's
 * scope.
 */
public class RealtimeCacheProjector(
    private val source: EventSource,
    private val writer: SessionCacheWriter,
    private val scope: CacheScope,
) {
    private var appliedSnapshot: RealtimeSnapshot? = null

    /** Starts both collectors in [coroutineScope] and returns their job. */
    public fun start(coroutineScope: CoroutineScope): Job = coroutineScope.launch {
        launch { source.state.collect(::projectState) }
        launch { source.events.collect(::projectEvent) }
    }

    private suspend fun projectState(state: RealtimeState) {
        // Nothing is reconciled yet: do not seed a cursor from the initial Idle state.
        val snapshot = state.snapshot ?: return
        if (snapshot !== appliedSnapshot) {
            appliedSnapshot = snapshot
            snapshot.sessions.forEach { session ->
                guarded {
                    putSession(
                        CachedSession(
                            serverId = scope.serverId,
                            projectId = scope.projectId,
                            sessionId = session.id,
                            title = session.title,
                            parentSessionId = null,
                            createdAt = session.updatedAt ?: 0L,
                            updatedAt = session.updatedAt ?: 0L,
                        ),
                    )
                }
            }
        }
        guarded {
            putSyncMetadata(
                CachedSyncMetadata(
                    serverId = scope.serverId,
                    projectId = scope.projectId,
                    sessionId = PROJECT_SCOPE,
                    lastEventId = state.lastEventId,
                    snapshotVersion = state.eventCount,
                ),
            )
        }
    }

    private suspend fun projectEvent(event: ServerEvent) {
        guarded {
            putSyncMetadata(
                CachedSyncMetadata(
                    serverId = scope.serverId,
                    projectId = scope.projectId,
                    sessionId = PROJECT_SCOPE,
                    lastEventId = event.id,
                    snapshotVersion = event.sequence,
                ),
            )
        }
    }

    /**
     * Runs one write, swallowing only the offline read-only refusal (D8). The
     * cache is disposable and rebuilt from the next snapshot, so a dropped write
     * while offline is not an error and must not tear down the pipeline.
     */
    private suspend fun guarded(block: suspend SessionCacheWriter.() -> Unit) {
        try {
            writer.block()
        } catch (_: CacheMutationNotAllowedException) {
            // Intentional: offline is read-only.
        }
    }

    private companion object {
        /** Empty session id: the cursor is project-level (see [CachedSyncMetadata]). */
        const val PROJECT_SCOPE = ""
    }
}
