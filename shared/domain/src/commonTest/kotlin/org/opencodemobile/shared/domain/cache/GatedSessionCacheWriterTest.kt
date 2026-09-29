package org.opencodemobile.shared.domain.cache

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * D8: while offline, the gated writer must refuse every mutation and must not
 * touch the delegate, so nothing is written and nothing is queued.
 */
class GatedSessionCacheWriterTest {

    @Test
    fun offlineRefusesEveryMutation() = runBlocking {
        val gate = ConnectivityMutationGate(ConnectionState.Offline)
        val delegate = RecordingWriter()
        val writer = GatedSessionCacheWriter(gate, delegate)

        assertFailsWith<CacheMutationNotAllowedException> {
            writer.putServerConfig(CachedServerConfig("s", "h", 1, "Https"))
        }
        assertFailsWith<CacheMutationNotAllowedException> { writer.putProject(CachedProject("s", "p", "n")) }
        assertFailsWith<CacheMutationNotAllowedException> { writer.putSession(CachedSession("s", "p", "sess")) }
        assertFailsWith<CacheMutationNotAllowedException> {
            writer.putTranscriptMessage(CachedTranscriptMessage("s", "p", "sess", 1, "user", "hi"), 10)
        }
        assertFailsWith<CacheMutationNotAllowedException> { writer.putDraft(CachedDraft("s", "p", "sess", "d")) }
        assertFailsWith<CacheMutationNotAllowedException> {
            writer.putPreference(CachedPreference("s", "theme", "dark"))
        }
        assertFailsWith<CacheMutationNotAllowedException> {
            writer.putSyncMetadata(CachedSyncMetadata("s", "p", "sess"))
        }
        assertFailsWith<CacheMutationNotAllowedException> { writer.wipeServer("s") }

        assertFalse(delegate.called, "the delegate must not be reached while offline")
    }

    @Test
    fun onlineDelegatesMutations() = runBlocking {
        val gate = ConnectivityMutationGate(ConnectionState.Online)
        val delegate = RecordingWriter()
        val writer = GatedSessionCacheWriter(gate, delegate)

        writer.putPreference(CachedPreference("s", "theme", "dark"))

        assertTrue(delegate.called)
    }

    private class RecordingWriter : SessionCacheWriter {
        var called = false

        override suspend fun putServerConfig(config: CachedServerConfig) {
            called = true
        }

        override suspend fun putProject(project: CachedProject) {
            called = true
        }

        override suspend fun putSession(session: CachedSession) {
            called = true
        }

        override suspend fun putTranscriptMessage(message: CachedTranscriptMessage, keepLast: Int) {
            called = true
        }

        override suspend fun putDraft(draft: CachedDraft) {
            called = true
        }

        override suspend fun putPreference(preference: CachedPreference) {
            called = true
        }

        override suspend fun putSyncMetadata(metadata: CachedSyncMetadata) {
            called = true
        }

        override suspend fun wipeServer(serverId: String) {
            called = true
        }
    }
}
