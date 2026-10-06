package org.opencodemobile.shared.application.cache

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.opencodemobile.shared.data.cache.CacheStack
import org.opencodemobile.shared.domain.cache.ConnectionState
import org.opencodemobile.shared.domain.event.ConnectionPhase
import org.opencodemobile.shared.domain.event.EventSource
import org.opencodemobile.shared.domain.event.RealtimeSnapshot
import org.opencodemobile.shared.domain.event.RealtimeState
import org.opencodemobile.shared.domain.event.ServerEvent
import org.opencodemobile.shared.domain.event.SessionSnapshot
import org.opencodemobile.shared.persistence.cache.CacheDriverProvider
import org.opencodemobile.shared.persistence.db.Cache

/**
 * OPE-180 / D8: the positive half of the gate, through the production surface.
 *
 * The write path under test is exactly the one the app composes:
 * [CacheWritePipeline] over the [CacheStack]'s gated writer
 * (`CacheStack.writer()`), with the stack's own [CacheStack.gate]. The
 * [EventSource] is faked at its port, but nothing below it is: the pipeline is
 * the production one, the gate is the production gate, and the write is read back
 * from the real cache schema over an in-memory SQLite driver.
 *
 * This is the proof the security review (OPE-178, F2) asked for: with the
 * connection online a snapshot is written and can be read back, and with the
 * connection offline the same write is refused and the cache stays empty.
 */
@Suppress("InjectDispatcher") // JVM unit test: real dispatcher on purpose; DI is not wired in tests.
class CacheWritePipelineIntegrationTest {

    private val scope = CacheScope(serverId = "srv_1", projectId = "prj_1")

    private fun onlineSnapshot(title: String) = RealtimeState(
        phase = ConnectionPhase.Live,
        snapshot = RealtimeSnapshot(
            sessions = listOf(SessionSnapshot("ses_1", title, null, updatedAt = 7L)),
            statuses = emptyMap(),
        ),
        eventCount = 1L,
        lastEventId = "evt_1",
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun anOnlineConnectionWritesThroughTheGatedProductionWriter() = runTest {
        val source = FakeEventSource()
        val stack = CacheStack(InMemoryDriverProvider(), ioDispatcher = Dispatchers.Unconfined)
        CacheWritePipeline(
            source = source,
            writer = stack.writer(),
            gate = stack.gate,
            scope = scope,
        ).start(backgroundScope)
        runCurrent()

        source.emit(onlineSnapshot("Title"))
        runCurrent()

        assertEquals(ConnectionState.Online, stack.gate.connectionState())
        val cached = stack.sessions("srv_1", "prj_1").single()
        assertEquals("ses_1", cached.sessionId)
        assertEquals("Title", cached.title)
        assertTrue(
            stack.sessions("srv_1", "prj_1").isNotEmpty(),
            "an online projection must land in the cache",
        )
        assertEquals(
            "evt_1",
            stack.syncMetadata("srv_1", "prj_1", "")!!.lastEventId,
            "the projected state must be readable back through the production read port",
        )

        source.emit(ServerEvent(sequence = 2L, id = "evt_2", type = "message", payload = "{}"))
        runCurrent()
        assertEquals("evt_2", stack.syncMetadata("srv_1", "prj_1", "")!!.lastEventId)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun anOfflineConnectionRefusesTheSameWriteAndCachesNothing() = runTest {
        val source = FakeEventSource()
        val stack = CacheStack(InMemoryDriverProvider(), ioDispatcher = Dispatchers.Unconfined)
        CacheWritePipeline(
            source = source,
            writer = stack.writer(),
            gate = stack.gate,
            scope = scope,
        ).start(backgroundScope)
        runCurrent()

        source.emit(
            onlineSnapshot("Title").copy(phase = ConnectionPhase.Stopped),
        )
        runCurrent()

        assertEquals(ConnectionState.Offline, stack.gate.connectionState())
        assertTrue(
            stack.sessions("srv_1", "prj_1").isEmpty(),
            "an offline projection must be refused, not queued",
        )
        assertNull(
            stack.syncMetadata("srv_1", "prj_1", ""),
            "an offline projection must leave the cache untouched",
        )

        // The stream comes back: the next snapshot is accepted and written.
        source.emit(onlineSnapshot("Back").copy(phase = ConnectionPhase.Reconciling))
        runCurrent()
        assertEquals(ConnectionState.Online, stack.gate.connectionState())
        assertEquals("Back", stack.sessions("srv_1", "prj_1").single().title)
    }

    @Test
    fun theConnectionPhaseMappingIsFailClose() {
        assertEquals(ConnectionState.Online, ConnectionPhase.Reconciling.toConnectionState())
        assertEquals(ConnectionState.Online, ConnectionPhase.Live.toConnectionState())
        assertEquals(ConnectionState.Offline, ConnectionPhase.Polling.toConnectionState())
        assertEquals(ConnectionState.Offline, ConnectionPhase.Idle.toConnectionState())
        assertEquals(ConnectionState.Offline, ConnectionPhase.Stopped.toConnectionState())
    }

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

    private class InMemoryDriverProvider : CacheDriverProvider {
        override suspend fun createDriver(): SqlDriver {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Cache.Schema.create(driver)
            return driver
        }

        override suspend fun deleteLocalCache(): Boolean = true
    }
}
