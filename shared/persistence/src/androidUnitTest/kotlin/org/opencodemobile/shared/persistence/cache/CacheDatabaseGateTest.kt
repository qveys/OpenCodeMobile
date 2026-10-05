package org.opencodemobile.shared.persistence.cache

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.opencodemobile.shared.domain.cache.CacheMutationNotAllowedException
import org.opencodemobile.shared.domain.cache.CachedProject
import org.opencodemobile.shared.domain.cache.ConnectionState
import org.opencodemobile.shared.domain.cache.ConnectivityMutationGate
import org.opencodemobile.shared.domain.cache.MutationGate
import org.opencodemobile.shared.domain.cache.SessionCache
import org.opencodemobile.shared.domain.cache.SessionCacheWriter
import org.opencodemobile.shared.persistence.db.Cache

/**
 * OPE-172 / D8: the gate is structural at the cache boundary, not a convention.
 *
 * - [CacheDatabase.open] hands out the read port [SessionCache] only.
 * - [CacheDatabase.writer] is the only public way to reach a
 *   [SessionCacheWriter], and it always returns a writer wrapped in the D8 gate.
 * - The raw [SqlSessionCache] is `internal`, so a consumer in another module can
 *   never receive it (and can never cast the read port into an ungated writer).
 */
@Suppress("InjectDispatcher") // JVM unit test: real dispatcher on purpose; DI is not wired in tests.
class CacheDatabaseGateTest {

    @Test
    fun closedGateRefusesWritesWhileTheReadPortStaysUsable() = runBlocking {
        val database = CacheDatabase(InMemoryDriverProvider(), Dispatchers.Unconfined)
        val writer = database.writer(ConnectivityMutationGate(ConnectionState.Offline))

        assertFailsWith<CacheMutationNotAllowedException> {
            writer.putProject(CachedProject("s1", "p1", "proj"))
        }
        assertFailsWith<CacheMutationNotAllowedException> { writer.wipeServer("s1") }

        // Reading is offered independently of the gate and keeps working offline.
        assertTrue(database.open().projects("s1").isEmpty())
    }

    @Test
    fun openedGateDelegatesToTheCache() = runBlocking {
        val database = CacheDatabase(InMemoryDriverProvider(), Dispatchers.Unconfined)
        val writer = database.writer(ConnectivityMutationGate(ConnectionState.Online))

        writer.putProject(CachedProject("s1", "p1", "proj"))

        assertEquals("proj", database.open().projects("s1").single().name)
    }

    @Test
    fun everyWriterFactoryRequiresTheGate() {
        val writerFactories = CacheDatabase::class.java.declaredMethods.filter { it.name == "writer" }

        assertTrue(writerFactories.isNotEmpty(), "CacheDatabase must expose a gated writer factory")
        writerFactories.forEach { method ->
            assertTrue(
                method.parameterTypes.any { MutationGate::class.java == it },
                "CacheDatabase.${method.name} returns a SessionCacheWriter without a MutationGate",
            )
        }
    }

    /**
     * Guards the structural invariants the compiler and `internal` visibility enforce:
     * the raw cache is internal, and the read factory is declared as the read port.
     * (A suspend function erases its return type to `Object` on the JVM, so reflection
     * cannot read the declared type; the source is the stable proof.)
     */
    @Test
    fun theRawCacheIsInternalAndReadsAreTypedAsThePort() {
        val databaseSource = File(DATABASE_SOURCE_PATH).readText()
        val cacheSource = File(SQL_CACHE_SOURCE_PATH).readText()

        assertTrue(
            databaseSource.contains("public suspend fun open(): SessionCache"),
            "open() must be declared as the read port, not the concrete cache",
        )
        assertTrue(
            cacheSource.contains("internal class SqlSessionCache"),
            "SqlSessionCache must be internal so no other module can obtain an ungated writer",
        )
    }

    private class InMemoryDriverProvider : CacheDriverProvider {
        override suspend fun createDriver(): SqlDriver {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Cache.Schema.create(driver)
            return driver
        }

        override suspend fun deleteLocalCache(): Boolean = true
    }

    private companion object {
        // Unit tests run with the module directory as the working directory.
        const val DATABASE_SOURCE_PATH =
            "src/commonMain/kotlin/org/opencodemobile/shared/persistence/cache/CacheDatabase.kt"
        const val SQL_CACHE_SOURCE_PATH =
            "src/commonMain/kotlin/org/opencodemobile/shared/persistence/cache/SqlSessionCache.kt"
    }
}
