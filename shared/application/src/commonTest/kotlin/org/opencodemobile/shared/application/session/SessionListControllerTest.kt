package org.opencodemobile.shared.application.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.opencodemobile.shared.domain.cache.CachedDraft
import org.opencodemobile.shared.domain.cache.CachedProject
import org.opencodemobile.shared.domain.cache.CachedServerConfig
import org.opencodemobile.shared.domain.cache.CachedSession
import org.opencodemobile.shared.domain.cache.CachedSyncMetadata
import org.opencodemobile.shared.domain.cache.CachedTranscriptMessage
import org.opencodemobile.shared.domain.cache.ConnectivityMutationGate
import org.opencodemobile.shared.domain.cache.ConnectionState
import org.opencodemobile.shared.domain.cache.SessionCache
import org.opencodemobile.shared.domain.session.ServerUnavailableException
import org.opencodemobile.shared.domain.session.SessionCapabilities
import org.opencodemobile.shared.domain.session.SessionGateway
import org.opencodemobile.shared.domain.session.SessionSummary
import org.opencodemobile.shared.domain.session.SessionTitlePolicy

private val scope = SessionsScope(serverId = "srv_1", projectId = "prj_1")

private fun summary(id: String, title: String, updatedAt: Long) = SessionSummary(
    id = id,
    title = title,
    projectId = scope.projectId,
    updatedAt = updatedAt,
)

private class FakeSessionGateway(
    var capabilities: SessionCapabilities = SessionCapabilities(forkAvailable = true),
    var listFailure: Throwable? = null,
    var mutationFailure: Throwable? = null,
) : SessionGateway {
    val calls: MutableList<String> = mutableListOf()
    var sessions: MutableList<SessionSummary> = mutableListOf()

    override suspend fun listSessions(directory: String?): List<SessionSummary> {
        calls += "list"
        listFailure?.let { throw it }
        return sessions.toList()
    }

    override suspend fun getSession(sessionId: String): SessionSummary {
        calls += "get:$sessionId"
        mutationFailure?.let { throw it }
        return sessions.first { it.id == sessionId }
    }

    override suspend fun createSession(directory: String?, title: String?): SessionSummary {
        calls += "create"
        mutationFailure?.let { throw it }
        val created = summary("ses_created", title ?: "Untitled", 10L)
        sessions += created
        return created
    }

    override suspend fun renameSession(sessionId: String, title: String): SessionSummary {
        calls += "rename:$sessionId:$title"
        mutationFailure?.let { throw it }
        val index = sessions.indexOfFirst { it.id == sessionId }
        val renamed = sessions[index].copy(title = title, updatedAt = 20L)
        sessions[index] = renamed
        return renamed
    }

    override suspend fun deleteSession(sessionId: String) {
        calls += "delete:$sessionId"
        mutationFailure?.let { throw it }
        sessions.removeAll { it.id == sessionId }
    }

    override suspend fun forkSession(sessionId: String): SessionSummary {
        calls += "fork:$sessionId"
        mutationFailure?.let { throw it }
        val forked = summary("ses_forked", "Fork of $sessionId", 30L).copy(parentSessionId = sessionId)
        sessions += forked
        return forked
    }

    override suspend fun sessionCapabilities(directory: String?): SessionCapabilities {
        calls += "capabilities"
        return capabilities
    }
}

private class FakeSessionCache(
    private val cached: MutableList<CachedSession> = mutableListOf(),
    var sessionsFailure: Throwable? = null,
) : SessionCache {
    override suspend fun serverConfig(serverId: String): CachedServerConfig? = null
    override suspend fun projects(serverId: String): List<CachedProject> = emptyList()
    override suspend fun sessions(serverId: String, projectId: String): List<CachedSession> {
        sessionsFailure?.let { throw it }
        return cached.toList()
    }
    override suspend fun session(serverId: String, projectId: String, sessionId: String): CachedSession? = null
    override suspend fun recentTranscript(
        serverId: String,
        projectId: String,
        sessionId: String,
        limit: Int,
    ): List<CachedTranscriptMessage> = emptyList()
    override suspend fun draft(serverId: String, projectId: String, sessionId: String): CachedDraft? = null
    override suspend fun preference(serverId: String, key: String): String? = null
    override suspend fun syncMetadata(
        serverId: String,
        projectId: String,
        sessionId: String,
    ): CachedSyncMetadata? = null
}

private fun cached(vararg ids: String): MutableList<CachedSession> = ids.mapIndexed { index, id ->
    CachedSession(
        serverId = scope.serverId,
        projectId = scope.projectId,
        sessionId = id,
        title = "Cached $id",
        createdAt = index.toLong(),
        updatedAt = index.toLong(),
    )
}.toMutableList()

private class Fixture {
    val gateway = FakeSessionGateway()
    val cache = FakeSessionCache()
    val gate = ConnectivityMutationGate(ConnectionState.Online)
    val controller = SessionListController(
        gateway = gateway,
        cache = cache,
        gate = gate,
        scope = { scope },
    )
}

class SessionListControllerTest {

    @Test
    fun onlineRefreshReadsTheServerAndTheCapabilitySurface() = runTest {
        val fixture = Fixture()
        fixture.gateway.sessions = mutableListOf(
            summary("ses_1", "First", 10L),
            summary("ses_2", "Second", 20L),
        )

        fixture.controller.refresh()

        val state = fixture.controller.state.value
        assertEquals(listOf("ses_2", "ses_1"), state.sessions.map { it.id }, "newest activity first")
        assertEquals(SessionListSource.Live, state.source)
        assertTrue(state.online)
        assertFalse(state.offline)
        assertTrue(state.forkAvailable)
        assertNull(state.error)
        assertTrue(fixture.gateway.calls.contains("list"))
        assertTrue(fixture.gateway.calls.contains("capabilities"))
    }

    @Test
    fun offlineRefreshRendersTheCacheAndOffersNoMutation() = runTest {
        val fixture = Fixture()
        val offlineCache = FakeSessionCache(cached("ses_cached_1", "ses_cached_2"))
        val controller = SessionListController(fixture.gateway, offlineCache, fixture.gate, { scope })
        fixture.gate.onConnectionStateChanged(ConnectionState.Offline)

        controller.refresh()

        val state = controller.state.value
        assertEquals(2, state.sessions.size)
        assertEquals(SessionListSource.Cache, state.source)
        assertTrue(state.offline)
        assertFalse(state.forkAvailable)
        assertTrue(fixture.gateway.calls.isEmpty(), "offline must touch no server route")

        controller.createSession("New")
        assertEquals(SessionListError.Offline, controller.state.value.error)
        controller.deleteSession("ses_cached_1")
        assertEquals(SessionListError.Offline, controller.state.value.error)
        controller.forkSession("ses_cached_1")
        assertEquals(SessionListError.Offline, controller.state.value.error)
        assertTrue(fixture.gateway.calls.isEmpty(), "no mutation reaches the gateway offline")
    }

    @Test
    fun serverUnavailableFallsBackToTheCacheWithAnActionableError() = runTest {
        val fixture = Fixture()
        val cache = FakeSessionCache(cached("ses_cached_1"))
        fixture.gateway.listFailure = ServerUnavailableException()
        val controller = SessionListController(fixture.gateway, cache, fixture.gate, { scope })

        controller.refresh()

        val state = controller.state.value
        assertEquals(SessionListError.ServerUnavailable, state.error)
        assertEquals(1, state.sessions.size, "the cache still fills the screen")
        assertEquals(SessionListSource.Cache, state.source)
    }

    @Test
    fun createRenameDeleteAndForkAreReflectedAfterReload() = runTest {
        val fixture = Fixture()
        fixture.gateway.sessions = mutableListOf(summary("ses_1", "First", 10L))

        fixture.controller.refresh()
        fixture.controller.createSession("Created")
        assertTrue(fixture.controller.state.value.sessions.any { it.id == "ses_created" })

        fixture.controller.renameSession("ses_created", "  Renamed   title  ")
        val renamed = fixture.controller.state.value.sessions.first { it.id == "ses_created" }
        assertEquals("Renamed title", renamed.title)

        fixture.controller.forkSession("ses_created")
        assertTrue(fixture.controller.state.value.sessions.any { it.parentSessionId == "ses_created" })

        fixture.controller.deleteSession("ses_created")
        assertFalse(fixture.controller.state.value.sessions.any { it.id == "ses_created" })

        assertTrue(fixture.gateway.calls.contains("create"))
        assertTrue(fixture.gateway.calls.contains("rename:ses_created:Renamed title"))
        assertTrue(fixture.gateway.calls.contains("fork:ses_created"))
        assertTrue(fixture.gateway.calls.contains("delete:ses_created"))
    }

    @Test
    fun unreadableCacheRendersAnEmptyReadOnlyStateInsteadOfThrowing() = runTest {
        val fixture = Fixture()
        val brokenCache = FakeSessionCache(sessionsFailure = IllegalStateException("cache key invalidated"))
        val controller = SessionListController(fixture.gateway, brokenCache, fixture.gate, { scope })
        fixture.gate.onConnectionStateChanged(ConnectionState.Offline)

        controller.refresh()

        assertTrue(controller.state.value.sessions.isEmpty())
        assertTrue(controller.state.value.offline)
    }

    @Test
    fun recoveredMutationRereadsTheCapabilitySurface() = runTest {
        val fixture = Fixture()
        fixture.gateway.listFailure = IllegalStateException("down")
        fixture.controller.refresh()
        assertFalse(fixture.controller.state.value.forkAvailable)
        fixture.gateway.listFailure = null

        fixture.controller.createSession("Back")

        assertTrue(fixture.controller.state.value.forkAvailable)
    }

    @Test
    fun forkIsRefusedLocallyWhenTheServerDoesNotExposeIt() = runTest {
        val fixture = Fixture()
        fixture.gateway.capabilities = SessionCapabilities(forkAvailable = false)
        fixture.controller.refresh()

        fixture.controller.forkSession("ses_1")

        assertEquals(
            SessionListError.Rejected("This server does not expose session forking"),
            fixture.controller.state.value.error,
        )
        assertFalse(
            fixture.gateway.calls.any { it.startsWith("fork:") },
            "a server without fork must see no rejected call",
        )
    }

    @Test
    fun renameRejectsABlankTitleBeforeTouchingTheGateway() = runTest {
        val fixture = Fixture()
        fixture.controller.refresh()

        fixture.controller.renameSession("ses_1", "   ")

        assertTrue(fixture.controller.state.value.error is SessionListError.Rejected)
        assertFalse(fixture.gateway.calls.any { it.startsWith("rename:") })
    }

    @Test
    fun createRejectsATitleLongerThanThePolicyBeforeTouchingTheGateway() = runTest {
        val fixture = Fixture()
        fixture.controller.refresh()

        fixture.controller.createSession("a".repeat(SessionTitlePolicy.MAX_LENGTH + 1))

        assertTrue(fixture.controller.state.value.error is SessionListError.Rejected)
        assertFalse(fixture.gateway.calls.contains("create"))
    }

    @Test
    fun openSessionReturnsTheServerRowOnlineAndTheCachedRowOffline() = runTest {
        val fixture = Fixture()
        fixture.gateway.sessions = mutableListOf(summary("ses_1", "First", 10L))
        fixture.controller.refresh()

        val online = fixture.controller.openSession("ses_1")
        assertNotNull(online)
        assertEquals("First", online.title)

        val cache = FakeSessionCache(cached("ses_cached_1"))
        val offlineGate = ConnectivityMutationGate(ConnectionState.Offline)
        val offline = SessionListController(fixture.gateway, cache, offlineGate, { scope })
        offline.refresh()

        val resumed = offline.openSession("ses_cached_1")
        assertNotNull(resumed)
        assertEquals("Cached ses_cached_1", resumed.title)
    }
}
