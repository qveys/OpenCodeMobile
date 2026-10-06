package org.opencodemobile.shared.data.permission

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.opencodemobile.shared.domain.cache.CachedDraft
import org.opencodemobile.shared.domain.cache.CachedPreference
import org.opencodemobile.shared.domain.cache.CachedProject
import org.opencodemobile.shared.domain.cache.CachedServerConfig
import org.opencodemobile.shared.domain.cache.CachedSession
import org.opencodemobile.shared.domain.cache.CachedSyncMetadata
import org.opencodemobile.shared.domain.cache.CachedTranscriptMessage
import org.opencodemobile.shared.domain.cache.SessionCache
import org.opencodemobile.shared.domain.cache.SessionCacheWriter
import org.opencodemobile.shared.domain.permission.PermissionCapabilities
import org.opencodemobile.shared.domain.permission.PermissionRequest

class CachePendingPermissionStoreTest {

    private val request = PermissionRequest(
        id = "per_mock_0001",
        sessionId = "ses_mock_0001",
        tool = "bash",
        patterns = listOf("rm -rf build"),
        rawArguments = """{"command":"rm -rf build"}""",
        rememberScopes = listOf("bash:rm"),
        capabilities = PermissionCapabilities.fromServer(listOf("bash:rm")),
    )

    @Test
    fun pendingRequestRoundTripsThroughTheCache() = runTest {
        val cache = InMemoryKeyValueCache()
        val store = CachePendingPermissionStore(cache, cache, serverId = "srv_1")

        store.save(listOf(request))

        assertEquals(listOf(request), store.load())
        assertTrue(
            cache.preferences.containsKey("srv_1" to PENDING_PERMISSION_PREFERENCE_KEY),
            "the pending set is stored under the documented preference key",
        )
    }

    @Test
    fun theServerExposedDecisionSetSurvivesARestart() = runTest {
        val cache = InMemoryKeyValueCache()
        val store = CachePendingPermissionStore(cache, cache, serverId = "srv_1")
        val onceOnly = request.copy(capabilities = PermissionCapabilities.OnceOnly)

        store.save(listOf(onceOnly))

        assertEquals(PermissionCapabilities.OnceOnly, store.load().single().capabilities)
    }

    @Test
    fun missingOrCorruptValuesDegradeToEmptyNeverToAFabricatedRequest() = runTest {
        val cache = InMemoryKeyValueCache()
        val store = CachePendingPermissionStore(cache, cache, serverId = "srv_1")

        assertTrue(store.load().isEmpty(), "no stored value means no pending request")

        cache.preferences["srv_1" to PENDING_PERMISSION_PREFERENCE_KEY] = "not-json"
        assertTrue(store.load().isEmpty(), "a corrupt value must not fabricate a request")
    }

    @Test
    fun savingAnEmptySetClearsThePendingList() = runTest {
        val cache = InMemoryKeyValueCache()
        val store = CachePendingPermissionStore(cache, cache, serverId = "srv_1")
        store.save(listOf(request))

        store.save(emptyList())

        assertTrue(store.load().isEmpty())
    }
}

/**
 * Minimal fake of the cache surface the store uses. Every other method is
 * unreachable from [CachePendingPermissionStore] and deliberately fails loudly.
 */
private class InMemoryKeyValueCache : SessionCache, SessionCacheWriter {
    val preferences: MutableMap<Pair<String, String>, String> = mutableMapOf()

    override suspend fun preference(serverId: String, key: String): String? =
        preferences[serverId to key]

    override suspend fun putPreference(preference: CachedPreference) {
        preferences[preference.serverId to preference.key] = preference.value
    }

    override suspend fun serverConfig(serverId: String): CachedServerConfig? = unreachable()

    override suspend fun projects(serverId: String): List<CachedProject> = unreachable()

    override suspend fun sessions(serverId: String, projectId: String): List<CachedSession> = unreachable()

    override suspend fun session(
        serverId: String,
        projectId: String,
        sessionId: String,
    ): CachedSession? = unreachable()

    override suspend fun recentTranscript(
        serverId: String,
        projectId: String,
        sessionId: String,
        limit: Int,
    ): List<CachedTranscriptMessage> = unreachable()

    override suspend fun draft(serverId: String, projectId: String, sessionId: String): CachedDraft? =
        unreachable()

    override suspend fun syncMetadata(
        serverId: String,
        projectId: String,
        sessionId: String,
    ): CachedSyncMetadata? = unreachable()

    override suspend fun putServerConfig(config: CachedServerConfig): Unit = unreachable()

    override suspend fun putProject(project: CachedProject): Unit = unreachable()

    override suspend fun putSession(session: CachedSession): Unit = unreachable()

    override suspend fun putTranscriptMessage(
        message: CachedTranscriptMessage,
        keepLast: Int,
    ): Unit = unreachable()

    override suspend fun putDraft(draft: CachedDraft): Unit = unreachable()

    override suspend fun putSyncMetadata(metadata: CachedSyncMetadata): Unit = unreachable()

    override suspend fun wipeServer(serverId: String): Unit = unreachable()

    private fun unreachable(): Nothing =
        error("the pending-permission store must only touch the preference table")
}
