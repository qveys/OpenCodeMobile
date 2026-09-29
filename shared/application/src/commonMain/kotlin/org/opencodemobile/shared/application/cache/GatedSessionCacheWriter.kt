package org.opencodemobile.shared.application.cache

import org.opencodemobile.shared.domain.cache.CacheMutationNotAllowedException
import org.opencodemobile.shared.domain.cache.CachedDraft
import org.opencodemobile.shared.domain.cache.CachedPreference
import org.opencodemobile.shared.domain.cache.CachedProject
import org.opencodemobile.shared.domain.cache.CachedServerConfig
import org.opencodemobile.shared.domain.cache.CachedSession
import org.opencodemobile.shared.domain.cache.CachedSyncMetadata
import org.opencodemobile.shared.domain.cache.CachedTranscriptMessage
import org.opencodemobile.shared.domain.cache.MutationGate
import org.opencodemobile.shared.domain.cache.SessionCacheWriter

/**
 * D8 enforcement point for the cache: a [SessionCacheWriter] that refuses every
 * mutation while the [MutationGate] says the app is offline.
 *
 * The cache is written only from server events and snapshots; wrapping the
 * writer with this decorator makes the offline read-only rule structural rather
 * than a convention. The event/snapshot pipeline takes this gated writer, so an
 * offline caller fails fast with [CacheMutationNotAllowedException] and nothing
 * is queued (there is no outbox).
 */
public class GatedSessionCacheWriter(
    private val gate: MutationGate,
    private val delegate: SessionCacheWriter,
) : SessionCacheWriter {

    private fun requireMutationsAllowed() {
        if (!gate.mutationsAllowed()) {
            throw CacheMutationNotAllowedException()
        }
    }

    override suspend fun putServerConfig(config: CachedServerConfig) {
        requireMutationsAllowed()
        delegate.putServerConfig(config)
    }

    override suspend fun putProject(project: CachedProject) {
        requireMutationsAllowed()
        delegate.putProject(project)
    }

    override suspend fun putSession(session: CachedSession) {
        requireMutationsAllowed()
        delegate.putSession(session)
    }

    override suspend fun putTranscriptMessage(
        message: CachedTranscriptMessage,
        keepLast: Int,
    ) {
        requireMutationsAllowed()
        delegate.putTranscriptMessage(message, keepLast)
    }

    override suspend fun putDraft(draft: CachedDraft) {
        requireMutationsAllowed()
        delegate.putDraft(draft)
    }

    override suspend fun putPreference(preference: CachedPreference) {
        requireMutationsAllowed()
        delegate.putPreference(preference)
    }

    override suspend fun putSyncMetadata(metadata: CachedSyncMetadata) {
        requireMutationsAllowed()
        delegate.putSyncMetadata(metadata)
    }

    override suspend fun wipeServer(serverId: String) {
        requireMutationsAllowed()
        delegate.wipeServer(serverId)
    }
}
