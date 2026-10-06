package org.opencodemobile.shared.data.chat

import org.opencodemobile.shared.data.cache.CacheStack
import org.opencodemobile.shared.domain.cache.CachedDraft
import org.opencodemobile.shared.domain.cache.SessionCache
import org.opencodemobile.shared.domain.chat.ComposerDraftStore

/**
 * Encrypted-cache implementation of [ComposerDraftStore] (§8.1).
 *
 * The draft is scoped `ServerId -> ProjectId -> SessionId` and stored through the
 * disposable encrypted cache, so it survives an app kill without ever holding a
 * secret. Writes go through the writer, which is the D8-gated one: while the app
 * is offline the write is refused and the composer keeps the draft in memory
 * only, which is exactly the read-only-offline policy.
 *
 * Reading, restoring, or storing a draft never sends anything: only
 * `ComposerController.send` can reach the wire.
 */
public class CacheComposerDraftStore(
    private val cache: SessionCache,
    private val cacheStack: CacheStack,
    private val serverId: String,
    private val projectId: String,
) : ComposerDraftStore {

    override suspend fun loadDraft(sessionId: String): String =
        cache.draft(serverId, projectId, sessionId)?.body.orEmpty()

    override suspend fun saveDraft(sessionId: String, draft: String) {
        cacheStack.writer().putDraft(
            CachedDraft(
                serverId = serverId,
                projectId = projectId,
                sessionId = sessionId,
                body = draft,
            ),
        )
    }
}
