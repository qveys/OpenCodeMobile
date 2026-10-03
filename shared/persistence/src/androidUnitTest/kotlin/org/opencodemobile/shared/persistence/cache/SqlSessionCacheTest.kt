package org.opencodemobile.shared.persistence.cache

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.opencodemobile.shared.domain.cache.CachedDraft
import org.opencodemobile.shared.domain.cache.CachedPreference
import org.opencodemobile.shared.domain.cache.CachedProject
import org.opencodemobile.shared.domain.cache.CachedServerConfig
import org.opencodemobile.shared.domain.cache.CachedSession
import org.opencodemobile.shared.domain.cache.CachedSyncMetadata
import org.opencodemobile.shared.domain.cache.CachedTranscriptMessage
import org.opencodemobile.shared.persistence.db.Cache

/**
 * JVM unit tests for the cache schema and queries (D8; OPE-107).
 *
 * These run over a plain in-memory SQLite driver: they prove scoping,
 * upsert/read-back, transcript pruning, the no-secret preference guard, and
 * wipe, and they assert the schema carries no secret column name. At-rest
 * encryption itself is proven on device by
 * `SqlCipherCacheEncryptionInstrumentedTest`.
 */
class SqlSessionCacheTest {

    private fun cache(): SqlSessionCache {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Cache.Schema.create(driver)
        return SqlSessionCache(driver, Dispatchers.Unconfined)
    }

    @Test
    fun `round-trips each cached entity`() = runBlocking {
        val cache = cache()

        cache.putServerConfig(CachedServerConfig("s1", "host.example", 4096, "Https", "Home"))
        cache.putProject(CachedProject("s1", "p1", "proj", "/work", 10L))
        cache.putSession(
            CachedSession("s1", "p1", "sess1", "Title", null, createdAt = 1L, updatedAt = 2L),
        )
        cache.putDraft(CachedDraft("s1", "p1", "sess1", "draft body", 3L))
        cache.putPreference(CachedPreference("s1", "theme", "dark"))
        cache.putSyncMetadata(CachedSyncMetadata("s1", "p1", "sess1", "evt-9", 4L, 5L))

        assertEquals("host.example", cache.serverConfig("s1")?.host)
        assertEquals(1, cache.projects("s1").size)
        assertEquals("Title", cache.session("s1", "p1", "sess1")?.title)
        assertEquals("draft body", cache.draft("s1", "p1", "sess1")?.body)
        assertEquals("dark", cache.preference("s1", "theme"))
        assertEquals("evt-9", cache.syncMetadata("s1", "p1", "sess1")?.lastEventId)
    }

    @Test
    fun `cache is scoped ServerId to ProjectId to SessionId`() = runBlocking {
        val cache = cache()
        cache.putSession(CachedSession("s1", "p1", "sess1"))
        cache.putSession(CachedSession("s1", "p2", "sess1"))
        cache.putSession(CachedSession("s2", "p1", "sess1"))

        assertEquals(1, cache.sessions("s1", "p1").size)
        assertEquals(1, cache.sessions("s1", "p2").size)
        assertEquals(1, cache.sessions("s2", "p1").size)
        assertEquals(0, cache.sessions("s2", "p2").size)
        assertNull(cache.session("s2", "p2", "sess1"))
    }

    @Test
    fun `preferences are scoped by server`() = runBlocking {
        val cache = cache()
        cache.putPreference(CachedPreference("s1", "theme", "dark"))
        cache.putPreference(CachedPreference("s2", "theme", "light"))

        assertEquals("dark", cache.preference("s1", "theme"))
        assertEquals("light", cache.preference("s2", "theme"))
        assertNull(cache.preference("s1", "language"))
    }

    @Test
    fun `recent transcript is returned in ascending order and pruned to keepLast`() = runBlocking {
        val cache = cache()
        repeat(10) { index ->
            cache.putTranscriptMessage(
                CachedTranscriptMessage("s1", "p1", "sess1", index.toLong(), "user", "m$index"),
                keepLast = 5,
            )
        }

        val recent = cache.recentTranscript("s1", "p1", "sess1", limit = 5)
        assertEquals(listOf("m5", "m6", "m7", "m8", "m9"), recent.map { it.content })
    }

    @Test
    fun `wipeServer drops that server's rows, including preferences`() = runBlocking {
        val cache = cache()
        cache.putServerConfig(CachedServerConfig("s1", "a", 1, "Https"))
        cache.putServerConfig(CachedServerConfig("s2", "b", 2, "Https"))
        cache.putSession(CachedSession("s1", "p1", "sess1"))
        cache.putSession(CachedSession("s2", "p1", "sess2"))
        cache.putPreference(CachedPreference("s1", "theme", "dark"))
        cache.putPreference(CachedPreference("s2", "theme", "light"))

        cache.wipeServer("s1")

        assertNull(cache.serverConfig("s1"))
        assertTrue(cache.sessions("s1", "p1").isEmpty())
        assertNull(cache.preference("s1", "theme"))
        assertEquals("b", cache.serverConfig("s2")?.host)
        assertEquals(1, cache.sessions("s2", "p1").size)
        assertEquals("light", cache.preference("s2", "theme"))
    }

    @Test
    fun `putPreference refuses secret-bearing keys and persists nothing`() = runBlocking {
        val cache = cache()

        assertFailsWith<IllegalArgumentException> {
            cache.putPreference(CachedPreference("s1", "auth_token", "Bearer abc"))
        }
        assertNull(cache.preference("s1", "auth_token"))
    }

    @Test
    fun `schema declares no secret-bearing column name`() {
        val schemaFile = File(SCHEMA_PATH)
        assertTrue(schemaFile.isFile, "Cache.sq not found at $SCHEMA_PATH")

        val ddl = schemaFile.readText().lowercase()
        val forbidden = listOf(
            "password",
            "passphrase",
            "credential",
            "token",
            "secret",
            "api_key",
            "apikey",
            "authorization",
            "bearer",
            "private_key",
        )
        // Ignore comment lines: the header explains that no secret lives here.
        val columnsAndQueries = ddl
            .lineSequence()
            .filterNot { it.trimStart().startsWith("--") }
            .joinToString("\n")
        for (needle in forbidden) {
            assertFalse(
                columnsAndQueries.contains(needle),
                "Cache schema must not mention '$needle' (no secrets in the cache, §7.3)",
            )
        }
    }

    private companion object {
        // Unit tests run with the module directory as the working directory.
        const val SCHEMA_PATH =
            "src/commonMain/sqldelight/org/opencodemobile/shared/persistence/db/Cache.sq"
    }
}
