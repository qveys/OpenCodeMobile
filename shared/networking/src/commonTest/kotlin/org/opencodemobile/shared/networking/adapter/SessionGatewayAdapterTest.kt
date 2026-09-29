package org.opencodemobile.shared.networking.adapter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.opencodemobile.shared.domain.connection.ServerCredential
import org.opencodemobile.shared.domain.connection.ServerFingerprint
import org.opencodemobile.shared.domain.connection.ServerIdentityStore
import org.opencodemobile.shared.domain.connection.ServerIdentityVerifier
import org.opencodemobile.shared.domain.connection.ServerProfile
import org.opencodemobile.shared.domain.session.ServerUnavailableException
import org.opencodemobile.shared.domain.session.SessionNotFoundException
import org.opencodemobile.shared.security.identity.ServerIdentityGate
import org.opencodemobile.shared.security.identity.ServerIdentityPinController
import org.opencodemobile.shared.security.identity.TofuServerIdentityCoordinator
import org.opencodemobile.shared.testsupport.MockOpenCodeScenario
import org.opencodemobile.shared.testsupport.MockOpenCodeServer
import org.opencodemobile.shared.testsupport.OpenCodeFixtures

private class SessionEmptyIdentityStore : ServerIdentityStore {
    override suspend fun pinnedFingerprint(profileId: String): ServerFingerprint? = null
    override suspend fun storePinnedFingerprint(profileId: String, fingerprint: ServerFingerprint): Unit = Unit
    override suspend fun clearPinnedFingerprint(profileId: String): Unit = Unit
}

private class SessionNeverProbedVerifier : ServerIdentityVerifier {
    override suspend fun presentedFingerprint(profile: ServerProfile): ServerFingerprint =
        error("a plaintext mock profile has no certificate; the verifier must never be reached")
}

/**
 * V1-04: the generated client, driven through [OpenCodeV2Adapter] against the
 * `normal` [MockOpenCodeServer] scenario, proves list/create/open/rename/delete/
 * fork and that each mutation is reflected in the list at reload.
 *
 * The `ForksIfAndOnlyIfTheServerExposesIt` test proves the fork capability comes
 * from the server's published surface rather than a hard-coded catalog.
 */
class SessionGatewayAdapterTest {

    private val profile = ServerProfile(
        id = "mock-profile",
        host = "mock.opencode.test",
        port = 4096,
        tls = ServerProfile.TlsMode.PlaintextHttp,
    )

    private fun adapterFor(server: MockOpenCodeServer): OpenCodeV2Adapter {
        val pin = ServerIdentityPinController()
        val gate = ServerIdentityGate(
            TofuServerIdentityCoordinator(SessionEmptyIdentityStore(), SessionNeverProbedVerifier()),
        )
        return OpenCodeV2Adapter(server.client, gate, pin)
    }

    @Test
    fun normalScenarioSupportsTheFullSessionCrudRoundTrip() = runTest {
        val server = MockOpenCodeServer(expectSuccess = true).start()
        try {
            val adapter = adapterFor(server)
            adapter.connect(profile, ServerCredential("s3cr3t"))

            val initial = adapter.listSessions()
            assertEquals(OpenCodeFixtures.sessions.size, initial.size)
            assertTrue(adapter.sessionCapabilities().forkAvailable)

            // create
            val created = adapter.createSession(title = "Created by V1-04")
            assertEquals("Created by V1-04", created.title)
            assertTrue(adapter.listSessions().any { it.id == created.id })

            // resume / open
            assertEquals("Created by V1-04", adapter.getSession(created.id).title)

            // rename
            val renamed = adapter.renameSession(created.id, "Renamed by V1-04")
            assertEquals("Renamed by V1-04", renamed.title)
            assertEquals(
                "Renamed by V1-04",
                adapter.listSessions().first { it.id == created.id }.title,
                "the rename must be reflected in the list at reload",
            )

            // fork
            val forked = adapter.forkSession(created.id)
            assertEquals(created.id, forked.parentSessionId)
            assertTrue(
                adapter.listSessions().any { it.id == forked.id && it.parentSessionId == created.id },
                "the fork must be reflected in the list at reload",
            )

            // delete
            adapter.deleteSession(created.id)
            assertFalse(
                adapter.listSessions().any { it.id == created.id },
                "the delete must be reflected in the list at reload",
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun forksIfAndOnlyIfTheServerExposesIt() = runTest {
        val server = MockOpenCodeServer(
            scenario = MockOpenCodeScenario.NoFork,
            expectSuccess = true,
        ).start()
        try {
            val adapter = adapterFor(server)
            adapter.connect(profile, ServerCredential("s3cr3t"))

            assertFalse(
                adapter.sessionCapabilities().forkAvailable,
                "a server whose published surface omits fork must report it unavailable",
            )
            // The list itself still works: no capability is conflated with availability.
            assertEquals(OpenCodeFixtures.sessions.size, adapter.listSessions().size)
            assertFalse(
                server.requests.any { it.endsWith("/fork") },
                "no fork call may be sent to a server that does not expose it",
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun renamingAnUnknownSessionSurfacesANotFoundFailure() = runTest {
        val server = MockOpenCodeServer(expectSuccess = true).start()
        try {
            val adapter = adapterFor(server)
            adapter.connect(profile, ServerCredential("s3cr3t"))

            assertFailsWith<SessionNotFoundException> {
                adapter.renameSession("ses_does_not_exist", "Title")
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun aServerFailureSurfacesServerUnavailable() = runTest {
        val engine = MockEngine { request ->
            if (request.url.encodedPath == "/global/health") {
                respond(
                    content = """{"healthy":true,"version":"1.18.32"}""",
                    status = HttpStatusCode.OK,
                    headers = jsonHeaders(),
                )
            } else {
                respond(
                    content = """{"error":{"type":"server_error","message":"boom"}}""",
                    status = HttpStatusCode.InternalServerError,
                    headers = jsonHeaders(),
                )
            }
        }
        val client = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json() }
        }
        val pin = ServerIdentityPinController()
        val gate = ServerIdentityGate(
            TofuServerIdentityCoordinator(SessionEmptyIdentityStore(), SessionNeverProbedVerifier()),
        )
        val adapter = OpenCodeV2Adapter(client, gate, pin)
        adapter.connect(profile, ServerCredential("s3cr3t"))

        assertFailsWith<ServerUnavailableException> {
            adapter.listSessions()
        }
    }

    private fun jsonHeaders() =
        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
}
