package org.opencodemobile.shared.application.cache

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.opencodemobile.shared.domain.cache.CachedDraft
import org.opencodemobile.shared.domain.cache.CachedPreference
import org.opencodemobile.shared.domain.cache.CachedProject
import org.opencodemobile.shared.domain.cache.CachedServerConfig
import org.opencodemobile.shared.domain.cache.CachedSession
import org.opencodemobile.shared.domain.cache.CachedSyncMetadata
import org.opencodemobile.shared.domain.cache.CachedTranscriptMessage
import org.opencodemobile.shared.domain.cache.ConnectionState
import org.opencodemobile.shared.domain.cache.ConnectivityMutationGate
import org.opencodemobile.shared.domain.cache.GatedSessionCacheWriter
import org.opencodemobile.shared.domain.cache.SessionCacheWriter
import org.opencodemobile.shared.domain.event.EventSource
import org.opencodemobile.shared.domain.event.RealtimeSnapshot
import org.opencodemobile.shared.domain.event.RealtimeState
import org.opencodemobile.shared.domain.event.ServerEvent
import org.opencodemobile.shared.domain.event.SessionSnapshot

private class FakeEventSource : EventSource {
    private val mutableState = MutableStateFlow(RealtimeState())
    private val mutableEvents = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 16)

    override val state: StateFlow<RealtimeState> = mutableState.asStateFlow()
    override val events: SharedFlow<ServerEvent> = mutableEvents.asSharedFlow()

    override fun start(scope: CoroutineScope) = Unit
    override fun stop() = Unit

    fun emit(state: RealtimeState) {
        mutableState.value = state
    }

    fun emit(event: ServerEvent): Boolean = mutableEvents.tryEmit(event)
}

private class RecordingWriter : SessionCacheWriter {
    val sessions = mutableListOf<CachedSession>()
    val syncMetadata = mutableListOf<CachedSyncMetadata>()

    override suspend fun putServerConfig(config: CachedServerConfig) = Unit
    override suspend fun putProject(project: CachedProject) = Unit

    override suspend fun putSession(session: CachedSession) {
        sessions += session
    }

    override suspend fun putTranscriptMessage(message: CachedTranscriptMessage, keepLast: Int) = Unit
    override suspend fun putDraft(draft: CachedDraft) = Unit
    override suspend fun putPreference(preference: CachedPreference) = Unit

    override suspend fun putSyncMetadata(metadata: CachedSyncMetadata) {
        syncMetadata += metadata
    }

    override suspend fun wipeServer(serverId: String) = Unit
}

/**
 * OPE-172, item 3: the cache is written only from the realtime pipeline, through
 * the gated writer.
 */
class RealtimeCacheProjectorTest {

    private val scope = CacheScope(serverId = "srv_1", projectId = "prj_1")

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun snapshotsSeedSessionsAndTheSyncCursor() = runTest {
        val source = FakeEventSource()
        val writer = RecordingWriter()
        RealtimeCacheProjector(source, writer, scope).start(backgroundScope)
        runCurrent()

        source.emit(
            RealtimeState(
                snapshot = RealtimeSnapshot(
                    sessions = listOf(SessionSnapshot("ses_1", "Title", null, updatedAt = 7L)),
                    statuses = emptyMap(),
                ),
                eventCount = 3L,
                lastEventId = "evt_3",
            ),
        )
        runCurrent()

        val session = writer.sessions.single()
        assertEquals("srv_1", session.serverId)
        assertEquals("prj_1", session.projectId)
        assertEquals("ses_1", session.sessionId)
        assertEquals("Title", session.title)
        assertEquals(7L, session.updatedAt)

        val cursor = writer.syncMetadata.last()
        assertEquals("srv_1", cursor.serverId)
        assertEquals("prj_1", cursor.projectId)
        assertEquals("", cursor.sessionId)
        assertEquals("evt_3", cursor.lastEventId)
        assertEquals(3L, cursor.snapshotVersion)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun normalizedEventsAdvanceTheSyncCursor() = runTest {
        val source = FakeEventSource()
        val writer = RecordingWriter()
        RealtimeCacheProjector(source, writer, scope).start(backgroundScope)
        runCurrent()

        source.emit(ServerEvent(sequence = 5L, id = "evt_5", type = "message", payload = "{}"))
        runCurrent()

        assertEquals("evt_5", writer.syncMetadata.last().lastEventId)
        assertEquals(5L, writer.syncMetadata.last().snapshotVersion)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aReconciledSnapshotIsProjectedOnce() = runTest {
        val source = FakeEventSource()
        val writer = RecordingWriter()
        RealtimeCacheProjector(source, writer, scope).start(backgroundScope)
        runCurrent()

        val snapshot = RealtimeSnapshot(
            sessions = listOf(SessionSnapshot("ses_1", "Title", null, null)),
            statuses = emptyMap(),
        )
        source.emit(RealtimeState(snapshot = snapshot, eventCount = 1L, lastEventId = "evt_1"))
        runCurrent()
        // A later event changes the cursor but not the snapshot: sessions are not rewritten.
        source.emit(RealtimeState(snapshot = snapshot, eventCount = 2L, lastEventId = "evt_2"))
        runCurrent()

        assertEquals(1, writer.sessions.size, "unchanged snapshots must not be re-upserted")
        assertEquals(2, writer.syncMetadata.size)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun offlineRefusalsAreSwallowedAndNothingIsWritten() = runTest {
        val source = FakeEventSource()
        val delegate = RecordingWriter()
        val gated = GatedSessionCacheWriter(ConnectivityMutationGate(ConnectionState.Offline), delegate)
        RealtimeCacheProjector(source, gated, scope).start(backgroundScope)
        runCurrent()

        source.emit(
            RealtimeState(
                snapshot = RealtimeSnapshot(
                    sessions = listOf(SessionSnapshot("ses_1", "Title", null, null)),
                    statuses = emptyMap(),
                ),
                eventCount = 1L,
                lastEventId = "evt_1",
            ),
        )
        source.emit(ServerEvent(sequence = 1L, id = "evt_1", type = "message", payload = "{}"))
        runCurrent()

        assertTrue(delegate.sessions.isEmpty(), "an offline gate must refuse the write")
        assertTrue(delegate.syncMetadata.isEmpty(), "an offline gate must refuse the write")
    }
}
