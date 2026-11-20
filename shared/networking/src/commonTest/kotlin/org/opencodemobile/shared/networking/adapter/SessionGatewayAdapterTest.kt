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
import kotlinx.serialization.json.*
import org.opencodemobile.shared.domain.chat.TranscriptRole
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
 * Fork is attempted directly: v2 publishes no capability document; a missing route
 * must produce a typed failure while session listing remains available.
 */
class SessionGatewayAdapterTest {

    private val profile = ServerProfile(
        id = "mock-profile",
        host = "localhost",
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
    fun anUnsupportedForkSurfacesNotFoundWithoutBreakingSessionListing() = runTest {
        val server = MockOpenCodeServer(
            scenario = MockOpenCodeScenario.NoFork,
            expectSuccess = true,
        ).start()
        try {
            val adapter = adapterFor(server)
            adapter.connect(profile, ServerCredential("s3cr3t"))

            assertEquals(OpenCodeFixtures.sessions.size, adapter.listSessions().size)
            assertFailsWith<SessionNotFoundException> {
                adapter.forkSession(OpenCodeFixtures.sessions.first().id)
            }
            assertTrue(server.requests.contains("POST /api/session/${OpenCodeFixtures.sessions.first().id}/fork"))

        } finally {
            server.stop()
        }
    }

    @Test
    fun createSendsTheDirectoryInTheLocationBody() = runTest {
        val server = MockOpenCodeServer(expectSuccess = true).start()
        try {
            val adapter = adapterFor(server)
            adapter.connect(profile, ServerCredential("s3cr3t"))

            val created = adapter.createSession(directory = "/home/dev/scoped", title = "Scoped session")

            assertEquals("Scoped session", created.title)
            // v2 creation scopes the session through location.directory in the body.
            assertEquals("/home/dev/scoped", created.directory)
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
            if (request.url.encodedPath == "/api/info") {
                respond(
                    content = """{"version":"2.0.6","pid":6,"urls":[],"paths":{"tmp":"/tmp"}}""",
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

    @Test
    fun sessionsFollowOpaqueCursorsAndPreserveDirectoryScoping() = runTest {
        val server = MockOpenCodeServer(expectSuccess = true).start()
        try {
            val adapter = adapterFor(server)
            adapter.connect(profile, ServerCredential("s3cr3t"))
            repeat(51) { adapter.createSession(directory = "/scoped", title = "Session $it") }
            val sessions = adapter.listSessions("/scoped")
            assertEquals(51, sessions.size)
            assertEquals(51, sessions.map { it.id }.toSet().size)
            assertTrue(sessions.all { it.directory == "/scoped" })
        } finally {
            server.stop()
        }
    }

    @Test
    fun transcriptFollowsCursorsDeduplicatesAndSortsChronologically() = runTest {
        val cursors = mutableListOf<String?>()
        fun message(id: String, role: String, time: Long) = buildJsonObject {
            put("id", id)
            put("type", role)
            putJsonObject("time") { put("created", time) }
            if (role == "user") put("text", id) else {
                put("agent", "build")
                putJsonObject("model") { put("id", OpenCodeFixtures.MODEL_ID); put("providerID", "anthropic") }
                putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", id) }) }
            }
        }
        val older = message("msg_older", "user", 1)
        val newer = message("msg_newer", "assistant", 2)
        val engine = MockEngine { request ->
            val content = if (request.url.encodedPath == "/api/info") OpenCodeFixtures.infoJson() else {
                assertEquals("/api/session/ses_mock_0001/message", request.url.encodedPath)
                val cursor = request.url.parameters["cursor"]
                cursors += cursor
                buildJsonObject {
                    put("data", if (cursor == null) JsonArray(listOf(newer)) else JsonArray(listOf(newer, older)))
                    putJsonObject("cursor") { put("next", if (cursor == null) JsonPrimitive("opaque-next") else JsonNull) }
                }.toString()
            }
            respond(content, HttpStatusCode.OK, jsonHeaders())
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json() } }
        try {
            val gate = ServerIdentityGate(TofuServerIdentityCoordinator(SessionEmptyIdentityStore(), SessionNeverProbedVerifier()))
            val adapter = OpenCodeV2Adapter(client, gate, ServerIdentityPinController())
            adapter.connect(profile, ServerCredential("s3cr3t"))
            val messages = adapter.transcript("ses_mock_0001")
            assertEquals(listOf(null, "opaque-next"), cursors)
            assertEquals(listOf("msg_older", "msg_newer"), messages.map { it.id })
            assertEquals(listOf(TranscriptRole.User, TranscriptRole.Assistant), messages.map { it.role })
            assertEquals(listOf("msg_older", "msg_newer"), messages.map { it.parts.single().text })
        } finally {
            client.close()
        }
    }

    @Test
    fun transcriptFiltersMetadataWithoutLosingTheNextPageAndKeepsConversationText() = runTest {
        // Required fields match the Session.Message.* schemas in opencode-server-v2.json.
        val metadata = """[
          {"id":"msg_idle","time":{"created":1},"type":"idle","outcome":"succeeded"},
          {"id":"msg_agent","time":{"created":2},"type":"agent-switched","agent":"build"},
          {"id":"msg_model","time":{"created":3},"type":"model-switched","model":{"id":"m","providerID":"p"}},
          {"id":"msg_location","time":{"created":4},"type":"location-switched","location":{"directory":"/tmp"}},
          {"id":"msg_compaction","time":{"created":5},"type":"compaction","status":"completed","reason":"auto","summary":"summary","recent":"recent"}
        ]"""
        val conversation = """[
          {"id":"msg_synthetic","time":{"created":6},"type":"synthetic","text":"context","description":"not the text"},
          {"id":"msg_system","time":{"created":7},"type":"system","text":"notice"},
          {"id":"msg_skill","time":{"created":8},"type":"skill","skill":"review","name":"Review","text":"instructions"},
          {"id":"msg_shell","time":{"created":9},"type":"shell","shellID":"sh_test","command":"pwd","status":"exited","exit":0,"output":{"output":"/tmp","cursor":4,"size":4,"truncated":false}},
          {"id":"msg_future","time":{"created":10},"type":"future-type"}
        ]"""
        val cursors = mutableListOf<String?>()
        val engine = MockEngine { request ->
            val body = if (request.url.encodedPath == "/api/info") OpenCodeFixtures.infoJson() else {
                assertEquals("/api/session/ses_mock_0001/message", request.url.encodedPath)
                val cursor = request.url.parameters["cursor"]
                cursors += cursor
                if (cursor == null) """{"data":$metadata,"cursor":{"next":"metadata-next"}}"""
                else """{"data":$conversation,"cursor":{"next":null}}"""
            }
            respond(body, HttpStatusCode.OK, jsonHeaders())
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        try {
            val gate = ServerIdentityGate(TofuServerIdentityCoordinator(SessionEmptyIdentityStore(), SessionNeverProbedVerifier()))
            val adapter = OpenCodeV2Adapter(client, gate, ServerIdentityPinController())
            adapter.connect(profile, ServerCredential("s3cr3t"))
            val messages = adapter.transcript("ses_mock_0001")
            assertEquals(listOf(null, "metadata-next"), cursors)
            assertEquals(listOf("msg_synthetic", "msg_system", "msg_skill", "msg_shell", "msg_future"), messages.map { it.id })
            assertEquals(listOf(TranscriptRole.System, TranscriptRole.System, TranscriptRole.System, TranscriptRole.Tool, TranscriptRole.Unknown), messages.map { it.role })
            assertEquals(listOf("context", "notice", "instructions"), messages.take(3).map { it.parts.single().text })
            assertEquals(listOf("pwd", "/tmp"), messages[3].parts.map { it.text })
        } finally {
            client.close()
        }
    }

    @Test
    fun syntheticFallsBackToDescriptionWhenTextIsAbsent() {
        // Tolerate older payloads omitting the text required by the current spec.
        val message = Json.decodeFromString<org.opencode.mobile.networking.client.generated.models.ApiMessage>(
            """{"id":"msg_fallback","time":{"created":1},"type":"synthetic","description":"fallback"}""",
        ).toDomain("ses_test")
        assertEquals(TranscriptRole.System, message?.role)
        assertEquals("fallback", message?.parts?.single()?.text)
    }

    private fun jsonHeaders() =
        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
}
