package org.opencodemobile.shared.networking.live

import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue
import org.opencodemobile.shared.domain.chat.ChatPrompt
import org.opencodemobile.shared.domain.chat.TranscriptMessage
import org.opencodemobile.shared.domain.chat.TranscriptRole
import org.opencodemobile.shared.domain.connection.BasicAuthCredential
import org.opencodemobile.shared.domain.connection.ConnectionHandshake
import org.opencodemobile.shared.domain.connection.ServerFingerprint
import org.opencodemobile.shared.domain.connection.ServerIdentityCheck
import org.opencodemobile.shared.domain.connection.ServerIdentityStore
import org.opencodemobile.shared.domain.connection.ServerIdentityVerifier
import org.opencodemobile.shared.domain.connection.ServerProfile
import org.opencodemobile.shared.domain.connection.ServerVersion
import org.opencodemobile.shared.domain.event.StreamSignal
import org.opencodemobile.shared.networking.adapter.OpenCodeV2Adapter
import org.opencodemobile.shared.networking.adapter.createOpenCodeGateway
import org.opencodemobile.shared.security.identity.ServerIdentityGate
import org.opencodemobile.shared.security.identity.ServerIdentityPinController
import org.opencodemobile.shared.security.identity.TofuServerIdentityCoordinator

/**
 * OPT-IN smoke test: the REAL [OpenCodeV2Adapter] (real OkHttp engine, real SPKI pin
 * enforcement, real generated client) against a live OpenCode 2.0.6 server behind a
 * Traefik Basic Auth proxy.
 *
 * READ-ONLY except [sendPromptOnThrowawaySession] (extra opt-in OC_LIVE_WRITE=1), which
 * creates, prompts and deletes its own throwaway session. Nothing else is written.
 *
 * Skipped unless `OC_LIVE_USER` and `OC_LIVE_PASS` are set. `OC_LIVE_HOST` /
 * `OC_LIVE_PORT` override the default target. Credentials are never logged and never
 * appear in an assertion message.
 *
 * T1 (the TOFU identity UX) is tested elsewhere. Here the [ServerIdentityGate] is the
 * real class fed by a store/verifier pair that auto-confirm: the verifier performs a
 * real TLS probe and the store is pre-pinned to that fingerprint, so the gate returns
 * Authorized and the engine then enforces the pin on the real handshake.
 */
class LiveServerSmokeTest {

    private val user = System.getenv("OC_LIVE_USER")
    private val pass = System.getenv("OC_LIVE_PASS")
    private val host = System.getenv("OC_LIVE_HOST") ?: "vps-dokploy.end-inconnu.ts.net"
    private val port = System.getenv("OC_LIVE_PORT")?.toIntOrNull() ?: 4096
    private val profile = ServerProfile(id = "live-smoke", host = host, port = port)

    private class Session(val adapter: OpenCodeV2Adapter, val handshake: ConnectionHandshake)

    private fun connected(write: Boolean = false, block: suspend (Session) -> Unit) {
        assumeTrue("OC_LIVE_USER / OC_LIVE_PASS not set: live smoke test skipped", !user.isNullOrBlank() && !pass.isNullOrBlank())
        assumeTrue("OC_LIVE_WRITE=1 not set: live write test skipped", !write || System.getenv("OC_LIVE_WRITE") == "1")
        runBlocking {
            val probed = probeFingerprint(profile)
            val store = object : ServerIdentityStore {
                override suspend fun pinnedFingerprint(profileId: String) = probed
                override suspend fun storePinnedFingerprint(profileId: String, fingerprint: ServerFingerprint) = Unit
                override suspend fun clearPinnedFingerprint(profileId: String) = Unit
            }
            val verifier = object : ServerIdentityVerifier {
                override suspend fun presentedFingerprint(profile: ServerProfile) = probed
            }
            val gate = ServerIdentityGate(TofuServerIdentityCoordinator(store, verifier))
            val adapter = createOpenCodeGateway(ServerIdentityPinController(), gate)
            val handshake = adapter.connect(profile, credential = null, basicAuth = BasicAuthCredential(user!!, pass!!))
            try {
                block(Session(adapter, handshake))
            } finally {
                adapter.disconnect()
            }
        }
    }

    @Test
    fun connectAcceptsServerVersionWithTrustedIdentity() = connected { s ->
        assertTrue(s.handshake.identity is ServerIdentityCheck.Trusted, "identity must be Trusted")
        assertEquals(2, s.handshake.version.major, "expected a 2.x server")
        assertTrue(s.handshake.version >= ServerVersion.parseOrNull("2.0.6")!!, "compat profile accepts 2.0.6+")
    }

    @Test
    fun sessionsPaginateAndDecodeRealModels() = connected { s ->
        val sessions = s.adapter.listSessions(directory = null)
        assertTrue(sessions.all { it.id.isNotBlank() }, "every session needs an id")
        assertEquals(sessions.size, sessions.map { it.id }.toSet().size, "session ids must be unique")
        sessions.firstOrNull()?.let { assertEquals(it.id, s.adapter.getSession(it.id).id) }
    }

    @Test
    fun activeSessionsAndSnapshotDecode() = connected { s ->
        val transport = s.adapter.eventTransport(profile.baseUrl)
        val statuses = transport.pollStatuses()
        val snapshot = transport.fetchSnapshot()
        assertTrue(statuses.keys.all { it.isNotBlank() })
        assertTrue(snapshot.sessions.all { it.id.isNotBlank() })
    }

    @Test
    fun catalogDecodes() = connected { s ->
        val catalog = s.adapter.serverCatalog(directory = null)
        assertTrue(catalog.agentsAvailable, "GET /api/agent must be exposed")
        assertTrue(catalog.agents.all { it.name.isNotBlank() })
        assertTrue(catalog.providers.all { it.id.isNotBlank() })
    }

    @Test
    fun pendingFormsDecode() = connected { s ->
        // GET /api/form only: never replied to or cancelled.
        s.adapter.pendingQuestions(directory = null)
    }

    @Test
    fun existingTranscriptsDecodeWithoutException() = connected { s ->
        val sessions = s.adapter.listSessions(directory = null).sortedByDescending { it.updatedAt }.take(MAX_TRANSCRIPTS)
        val roles = mutableSetOf<Any>()
        for (session in sessions) {
            val messages = s.adapter.transcript(session.id)
            assertEquals(messages.size, messages.map { it.id }.toSet().size, "message ids must be unique")
            messages.forEach { assertEquals(session.id, it.sessionId); roles += it.role }
        }
        println("LiveServerSmokeTest: ${sessions.size} transcripts decoded, roles seen = $roles")
    }

    @Test
    fun sseOpensThenStaysAliveWithoutInactivityGap() = connected { s ->
        val stream = s.adapter.eventTransport(profile.baseUrl).openEventStream()
        val arrivals = mutableListOf<Pair<Long, StreamSignal?>>()
        val start = System.nanoTime()
        withTimeoutOrNull(SSE_WINDOW_MS) {
            stream.takeWhile { true }.collect { arrivals += (System.nanoTime() - start) / NANOS_PER_MS to it.signal }
        }
        assertTrue(arrivals.isNotEmpty(), "no SSE item at all within the window")
        assertEquals(StreamSignal.Opened, arrivals.first().second, "server.connected must surface as Opened, not a business event")
        val gaps = (listOf(0L) + arrivals.map { it.first } + SSE_WINDOW_MS).zipWithNext { a, b -> b - a }
        assertTrue(gaps.max() < INACTIVITY_MS, "gap ${gaps.max()} ms >= $INACTIVITY_MS ms inactivity timeout")
        println("LiveServerSmokeTest: ${arrivals.size} SSE items, max gap ${gaps.max()} ms")
    }

    /** Real TLS probe (trust-all, handshake only, no request sent) returning the leaf SPKI SHA-256. */
    private fun probeFingerprint(profile: ServerProfile): ServerFingerprint {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }
        (context.socketFactory.createSocket(profile.host, profile.port) as SSLSocket).use { socket ->
            socket.startHandshake()
            val spki = socket.session.peerCertificates.first().publicKey.encoded
            return ServerFingerprint.of(MessageDigest.getInstance("SHA-256").digest(spki))
        }
    }

    /**
     * WRITE test (extra opt-in `OC_LIVE_WRITE=1`). Only touches the throwaway session it
     * creates itself; that exact id is deleted in `finally`. No retry loop, no SSE: waits
     * via REST polling (transcript + /api/session/active), bounded by [WRITE_WAIT_MS].
     */
    @Test
    fun sendPromptOnThrowawaySession() = connected(write = true) { s ->
        val created = s.adapter.createSession(directory = null, title = "smoke-test-delete-me")
        val id = created.id
        assertTrue(id.isNotBlank(), "created session needs an id")
        try {
            val catalog = s.adapter.serverCatalog(directory = null)
            val configured = catalog.agents.isNotEmpty() && catalog.providers.any { it.models.isNotEmpty() }
            println("LiveServerSmokeTest: created throwaway session $id, agents=${catalog.agents.size}, providers=${catalog.providers.size}")
            try {
                s.adapter.sendPrompt(id, ChatPrompt(text = PROMPT))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("LiveServerSmokeTest: prompt REFUSED (${e::class.simpleName}), model configured=$configured; no retry")
                assertTrue(!configured, "prompt refused although agents/models are configured")
                return@connected
            }
            val transport = s.adapter.eventTransport(profile.baseUrl)
            val deadline = System.nanoTime() + WRITE_WAIT_MS * NANOS_PER_MS
            var messages = emptyList<TranscriptMessage>()
            var busy = true
            while (System.nanoTime() < deadline) {
                messages = s.adapter.transcript(id)
                busy = id in transport.pollStatuses()
                if (messages.any { it.role == TranscriptRole.Assistant } && !busy) break
                delay(POLL_MS)
            }
            assertTrue(messages.all { it.sessionId == id }, "transcript must belong to the throwaway session")
            assertTrue(messages.any { it.role == TranscriptRole.User }, "accepted prompt must appear as a user message")
            val reply = messages.any { it.role == TranscriptRole.Assistant }
            println(
                "LiveServerSmokeTest: prompt ACCEPTED, roles=${messages.map { it.role }}, assistant reply=$reply, " +
                    "still busy=$busy, model configured=$configured" +
                    if (!reply) " (no reply within ${WRITE_WAIT_MS / 1000}s: server likely has no usable model)" else "",
            )
            if (configured) assertTrue(reply, "model configured but no assistant reply within ${WRITE_WAIT_MS / 1000}s")
        } finally {
            try {
                s.adapter.deleteSession(id)
                println("LiveServerSmokeTest: throwaway session $id deleted")
            } catch (e: Exception) {
                // Best-effort cleanup: never mask the test's own outcome from a finally block.
                println("LiveServerSmokeTest: CLEANUP FAILED (${e::class.simpleName}); delete session $id manually")
            }
        }
    }

    private companion object {
        const val PROMPT = "Reponds uniquement: ok"
        const val WRITE_WAIT_MS = 60_000L
        const val POLL_MS = 2_000L
        const val MAX_TRANSCRIPTS = 5
        const val SSE_WINDOW_MS = 20_000L
        const val INACTIVITY_MS = 30_000L
        const val NANOS_PER_MS = 1_000_000L
    }
}
