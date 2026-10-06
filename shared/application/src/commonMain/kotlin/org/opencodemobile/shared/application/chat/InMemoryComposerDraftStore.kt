package org.opencodemobile.shared.application.chat

import org.opencodemobile.shared.domain.chat.ComposerDraftStore

/**
 * Process-lifetime [ComposerDraftStore].
 *
 * It is the fallback used while the encrypted cache composition root is not
 * wired: the draft is still local and still never sent automatically, it just
 * does not survive an app kill. The encrypted-cache implementation
 * (`shared/data`) replaces it as soon as a connection scope exists.
 */
public class InMemoryComposerDraftStore : ComposerDraftStore {
    private val drafts: MutableMap<String, String> = mutableMapOf()

    override suspend fun loadDraft(sessionId: String): String = drafts[sessionId].orEmpty()

    override suspend fun saveDraft(sessionId: String, draft: String) {
        drafts[sessionId] = draft
    }
}
