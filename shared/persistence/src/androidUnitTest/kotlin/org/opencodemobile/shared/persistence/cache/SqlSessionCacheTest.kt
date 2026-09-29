package org.opencodemobile.shared.persistence.cache

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
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

    @Test
    fun `android backup rules exclude the db, companions, and the key blob`() {
        val expectedExcludes = setOf(
            "database" to "opencodemobile_cache.db",
            "database" to "opencodemobile_cache.db-wal",
            "database" to "opencodemobile_cache.db-shm",
            "database" to "opencodemobile_cache.db-journal",
            "sharedpref" to "opencodemobile_cache_key.xml",
        )
        for (path in listOf(DATA_EXTRACTION_RULES_PATH, BACKUP_RULES_PATH)) {
            val excludes = parseExcludes(File(path))
            for (expected in expectedExcludes) {
                assertTrue(
                    expected in excludes,
                    "$path must exclude domain=${expected.first} path=${expected.second}",
                )
            }
        }
    }

    @Test
    fun `android data extraction rules cover cloud backup and device transfer`() {
        val xml = File(DATA_EXTRACTION_RULES_PATH).readText()
        assertTrue(xml.contains("<cloud-backup>"))
        assertTrue(xml.contains("<device-transfer>"))
    }

    @Test
    fun `android manifest wires the cache backup rules`() {
        val manifest = File(MANIFEST_PATH)
        assertTrue(manifest.isFile, "AndroidManifest.xml not found at $MANIFEST_PATH")
        val xml = manifest.readText()
        assertTrue(xml.contains("@xml/opencodemobile_cache_data_extraction_rules"))
        assertTrue(xml.contains("@xml/opencodemobile_cache_backup_rules"))
    }

    /** Parses all `exclude` elements into `(domain, path)` pairs. */
    private fun parseExcludes(file: File): Set<Pair<String, String>> {
        assertTrue(file.isFile, "backup rule file not found at $file")
        val document = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = false }
            .newDocumentBuilder()
            .parse(file)
        val nodes = document.getElementsByTagName("exclude")
        return buildSet {
            for (index in 0 until nodes.length) {
                val element = nodes.item(index)
                val domain = element.attributes?.getNamedItem("domain")?.nodeValue ?: continue
                val path = element.attributes?.getNamedItem("path")?.nodeValue ?: continue
                add(domain to path)
            }
        }
    }

    private companion object {
        // Unit tests run with the module directory as the working directory.
        const val SCHEMA_PATH =
            "src/commonMain/sqldelight/org/opencodemobile/shared/persistence/db/Cache.sq"
        const val DATA_EXTRACTION_RULES_PATH =
            "src/androidMain/res/xml/opencodemobile_cache_data_extraction_rules.xml"
        const val BACKUP_RULES_PATH =
            "src/androidMain/res/xml/opencodemobile_cache_backup_rules.xml"
        const val MANIFEST_PATH = "../../androidApp/src/main/AndroidManifest.xml"
    }
}
