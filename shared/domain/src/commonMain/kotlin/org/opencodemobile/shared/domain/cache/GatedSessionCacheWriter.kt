package org.opencodemobile.shared.domain.cache

/**
 * D8 enforcement point for the cache: a [SessionCacheWriter] that refuses every
 * mutation while the [MutationGate] says the app is offline.
 *
 * The cache is written only from server events and snapshots; wrapping the
 * writer with this decorator makes the offline read-only rule structural rather
 * than a convention. It lives in `shared/domain` next to the [MutationGate] port
 * and [CacheMutationNotAllowedException] so the persistence layer, which may only
 * depend on the domain, can hand out an already-gated writer
 * (`CacheDatabase.writer(gate)`) and no consumer ever sees an ungated one.
 *
 * An offline caller fails fast with [CacheMutationNotAllowedException] and
 * nothing is queued (there is no outbox).
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
