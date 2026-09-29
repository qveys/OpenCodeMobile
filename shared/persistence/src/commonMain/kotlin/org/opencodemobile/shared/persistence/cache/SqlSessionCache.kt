package org.opencodemobile.shared.persistence.cache

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencodemobile.shared.domain.cache.CachePreferencePolicy
import org.opencodemobile.shared.domain.cache.CachedDraft
import org.opencodemobile.shared.domain.cache.CachedPreference
import org.opencodemobile.shared.domain.cache.CachedProject
import org.opencodemobile.shared.domain.cache.CachedServerConfig
import org.opencodemobile.shared.domain.cache.CachedSession
import org.opencodemobile.shared.domain.cache.CachedSyncMetadata
import org.opencodemobile.shared.domain.cache.CachedTranscriptMessage
import org.opencodemobile.shared.domain.cache.SessionCache
import org.opencodemobile.shared.domain.cache.SessionCacheWriter
import org.opencodemobile.shared.persistence.db.Cache
import org.opencodemobile.shared.persistence.db.Draft
import org.opencodemobile.shared.persistence.db.Project
import org.opencodemobile.shared.persistence.db.ServerConfig
import org.opencodemobile.shared.persistence.db.Session
import org.opencodemobile.shared.persistence.db.SyncMetadata
import org.opencodemobile.shared.persistence.db.TranscriptMessage

/**
 * SQLDelight-backed [SessionCache] and [SessionCacheWriter].
 *
 * The same instance serves reads (the offline, read-only surface) and writes
 * (the event/snapshot ingestion surface), but it is **internal**: no consumer
 * outside `shared/persistence` can obtain it, so no consumer can cast a read
 * port back into an ungated writer. Writes are handed out only through
 * [CacheDatabase.writer], which wraps this instance in
 * [org.opencodemobile.shared.domain.cache.GatedSessionCacheWriter] (D8).
 *
 * It holds no policy of its own: the offline read-only rule (D8) is enforced by
 * that gate, and the application only invokes the writer from server events and
 * snapshots.
 *
 * The driver handed in is already encrypted at rest: Android builds it over
 * SQLCipher with a Keystore-held passphrase, iOS over OS Data Protection. This
 * class therefore never sees key material.
 */
internal class SqlSessionCache(
    driver: SqlDriver,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : SessionCache, SessionCacheWriter {

    private val database: Cache = Cache(driver)
    private val queries = database.cacheQueries

    override suspend fun serverConfig(serverId: String): CachedServerConfig? =
        withContext(ioDispatcher) {
            queries.selectServerConfig(serverId).executeAsOneOrNull()?.toDomain()
        }

    override suspend fun projects(serverId: String): List<CachedProject> =
        withContext(ioDispatcher) {
            queries.selectProjects(serverId).executeAsList().map { it.toDomain() }
        }

    override suspend fun sessions(serverId: String, projectId: String): List<CachedSession> =
        withContext(ioDispatcher) {
            queries.selectSessions(serverId, projectId).executeAsList().map { it.toDomain() }
        }

    override suspend fun session(
        serverId: String,
        projectId: String,
        sessionId: String,
    ): CachedSession? = withContext(ioDispatcher) {
        queries.selectSession(serverId, projectId, sessionId).executeAsOneOrNull()?.toDomain()
    }

    override suspend fun recentTranscript(
        serverId: String,
        projectId: String,
        sessionId: String,
        limit: Int,
    ): List<CachedTranscriptMessage> = withContext(ioDispatcher) {
        queries.selectRecentTranscript(serverId, projectId, sessionId, limit.toLong())
            .executeAsList()
            .map { it.toDomain() }
            .asReversed()
    }

    override suspend fun draft(
        serverId: String,
        projectId: String,
        sessionId: String,
    ): CachedDraft? = withContext(ioDispatcher) {
        queries.selectDraft(serverId, projectId, sessionId).executeAsOneOrNull()?.toDomain()
    }

    override suspend fun preference(serverId: String, key: String): String? =
        withContext(ioDispatcher) {
            queries.selectPreference(serverId, key).executeAsOneOrNull()
        }

    override suspend fun syncMetadata(
        serverId: String,
        projectId: String,
        sessionId: String,
    ): CachedSyncMetadata? = withContext(ioDispatcher) {
        queries.selectSyncMetadata(serverId, projectId, sessionId).executeAsOneOrNull()?.toDomain()
    }

    override suspend fun putServerConfig(config: CachedServerConfig): Unit =
        withContext(ioDispatcher) {
            queries.upsertServerConfig(
                server_id = config.serverId,
                host = config.host,
                port = config.port.toLong(),
                tls_mode = config.tlsMode,
                label = config.label,
            )
        }

    override suspend fun putProject(project: CachedProject): Unit = withContext(ioDispatcher) {
        queries.upsertProject(
            server_id = project.serverId,
            project_id = project.projectId,
            name = project.name,
            path = project.path,
            updated_at = project.updatedAt,
        )
    }

    override suspend fun putSession(session: CachedSession): Unit = withContext(ioDispatcher) {
        queries.upsertSession(
            server_id = session.serverId,
            project_id = session.projectId,
            session_id = session.sessionId,
            title = session.title,
            parent_session_id = session.parentSessionId,
            created_at = session.createdAt,
            updated_at = session.updatedAt,
        )
    }

    override suspend fun putTranscriptMessage(
        message: CachedTranscriptMessage,
        keepLast: Int,
    ): Unit = withContext(ioDispatcher) {
        database.transaction {
            queries.upsertTranscriptMessage(
                server_id = message.serverId,
                project_id = message.projectId,
                session_id = message.sessionId,
                sequence = message.sequence,
                role = message.role,
                content = message.content,
                created_at = message.createdAt,
            )
            if (keepLast > 0) {
                queries.pruneTranscript(
                    server_id = message.serverId,
                    project_id = message.projectId,
                    session_id = message.sessionId,
                    server_id_ = message.serverId,
                    project_id_ = message.projectId,
                    session_id_ = message.sessionId,
                    value = keepLast.toLong(),
                )
            }
        }
    }

    override suspend fun putDraft(draft: CachedDraft): Unit = withContext(ioDispatcher) {
        queries.upsertDraft(
            server_id = draft.serverId,
            project_id = draft.projectId,
            session_id = draft.sessionId,
            body = draft.body,
            updated_at = draft.updatedAt,
        )
    }

    override suspend fun putPreference(preference: CachedPreference): Unit =
        withContext(ioDispatcher) {
            CachePreferencePolicy.requireNonSecretKey(preference.key)
            queries.upsertPreference(
                server_id = preference.serverId,
                key = preference.key,
                value_ = preference.value,
            )
        }

    override suspend fun putSyncMetadata(metadata: CachedSyncMetadata): Unit =
        withContext(ioDispatcher) {
            queries.upsertSyncMetadata(
                server_id = metadata.serverId,
                project_id = metadata.projectId,
                session_id = metadata.sessionId,
                last_event_id = metadata.lastEventId,
                snapshot_version = metadata.snapshotVersion,
                last_synced_at = metadata.lastSyncedAt,
            )
        }

    override suspend fun wipeServer(serverId: String): Unit = withContext(ioDispatcher) {
        database.transaction {
            queries.deletePreferencesForServer(serverId)
            queries.deleteTranscriptForServer(serverId)
            queries.deleteDraftsForServer(serverId)
            queries.deleteSessionsForServer(serverId)
            queries.deleteProjectsForServer(serverId)
            queries.deleteSyncForServer(serverId)
            queries.deleteServerConfig(serverId)
        }
    }
}

private fun ServerConfig.toDomain(): CachedServerConfig = CachedServerConfig(
    serverId = server_id,
    host = host,
    port = port.toInt(),
    tlsMode = tls_mode,
    label = label,
)

private fun Project.toDomain(): CachedProject = CachedProject(
    serverId = server_id,
    projectId = project_id,
    name = name,
    path = path,
    updatedAt = updated_at,
)

private fun Session.toDomain(): CachedSession = CachedSession(
    serverId = server_id,
    projectId = project_id,
    sessionId = session_id,
    title = title,
    parentSessionId = parent_session_id,
    createdAt = created_at,
    updatedAt = updated_at,
)

private fun TranscriptMessage.toDomain(): CachedTranscriptMessage = CachedTranscriptMessage(
    serverId = server_id,
    projectId = project_id,
    sessionId = session_id,
    sequence = sequence,
    role = role,
    content = content,
    createdAt = created_at,
)

private fun Draft.toDomain(): CachedDraft = CachedDraft(
    serverId = server_id,
    projectId = project_id,
    sessionId = session_id,
    body = body,
    updatedAt = updated_at,
)

private fun SyncMetadata.toDomain(): CachedSyncMetadata = CachedSyncMetadata(
    serverId = server_id,
    projectId = project_id,
    sessionId = session_id,
    lastEventId = last_event_id,
    snapshotVersion = snapshot_version,
    lastSyncedAt = last_synced_at,
)
