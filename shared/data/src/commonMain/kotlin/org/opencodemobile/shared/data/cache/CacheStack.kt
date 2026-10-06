package org.opencodemobile.shared.data.cache

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.opencodemobile.shared.domain.cache.CachedDraft
import org.opencodemobile.shared.domain.cache.CachedProject
import org.opencodemobile.shared.domain.cache.CachedServerConfig
import org.opencodemobile.shared.domain.cache.CachedSession
import org.opencodemobile.shared.domain.cache.CachedSyncMetadata
import org.opencodemobile.shared.domain.cache.CachedTranscriptMessage
import org.opencodemobile.shared.domain.cache.ConnectionState
import org.opencodemobile.shared.domain.cache.ConnectivityMutationGate
import org.opencodemobile.shared.domain.cache.SessionCache
import org.opencodemobile.shared.domain.cache.SessionCacheWriter
import org.opencodemobile.shared.persistence.cache.CacheDatabase
import org.opencodemobile.shared.persistence.cache.CacheDriverProvider

/**
 * The composed local-cache stack (OPE-172 / D8).
 *
 * It owns the [CacheDatabase] and the [ConnectivityMutationGate] and hands the
 * rest of the app exactly the ports it may use:
 *
 * - reads, as this [SessionCache] (a lazy delegator over [CacheDatabase.open]), and
 * - writes, only through [writer], which is already wrapped in the D8 gate.
 *
 * Composition roots (`androidApp` and the iOS host) build it once with their
 * platform [CacheDriverProvider] — SQLCipher + Keystore on Android, Data
 * Protection on iOS — so the cache stack is a live object graph instead of a
 * library only tests construct.
 */
@Suppress("TooManyFunctions")
// The facade deliberately exposes the whole composed cache port surface
// (SessionCache reads + the gated writer/wipe/close lifecycle): splitting it
// would leak CacheDatabase to the composition roots it is meant to hide.
public class CacheStack(
    driverProvider: CacheDriverProvider,
    initialConnectionState: ConnectionState = ConnectionState.Offline,
    ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : SessionCache {

    /** The single connection gate every mutation surface must consult (D8). */
    public val gate: ConnectivityMutationGate = ConnectivityMutationGate(initialConnectionState)

    private val database: CacheDatabase = CacheDatabase(driverProvider, ioDispatcher)

    /** The D8 write port: already gated, the only writer the app ever receives. */
    public suspend fun writer(): SessionCacheWriter = database.writer(gate)

    /** Wipes and rebuilds after key loss or a profile removal (never gated; returns reads). */
    public suspend fun wipeAndRebuild(): SessionCache = database.wipeAndRebuild()

    /** Closes the driver; the cache can be reopened later. */
    public suspend fun close(): Unit = database.close()

    override suspend fun serverConfig(serverId: String): CachedServerConfig? =
        database.open().serverConfig(serverId)

    override suspend fun projects(serverId: String): List<CachedProject> =
        database.open().projects(serverId)

    override suspend fun sessions(serverId: String, projectId: String): List<CachedSession> =
        database.open().sessions(serverId, projectId)

    override suspend fun session(serverId: String, projectId: String, sessionId: String): CachedSession? =
        database.open().session(serverId, projectId, sessionId)

    override suspend fun recentTranscript(
        serverId: String,
        projectId: String,
        sessionId: String,
        limit: Int,
    ): List<CachedTranscriptMessage> =
        database.open().recentTranscript(serverId, projectId, sessionId, limit)

    override suspend fun draft(serverId: String, projectId: String, sessionId: String): CachedDraft? =
        database.open().draft(serverId, projectId, sessionId)

    override suspend fun preference(serverId: String, key: String): String? =
        database.open().preference(serverId, key)

    override suspend fun syncMetadata(
        serverId: String,
        projectId: String,
        sessionId: String,
    ): CachedSyncMetadata? =
        database.open().syncMetadata(serverId, projectId, sessionId)
}
