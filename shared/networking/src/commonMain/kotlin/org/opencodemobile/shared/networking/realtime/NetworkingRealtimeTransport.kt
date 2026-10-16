package org.opencodemobile.shared.networking.realtime

import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.timeout

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.opencode.mobile.networking.client.generated.apis.OpenCodeApiClient
import org.opencode.mobile.networking.client.generated.models.ApiSession
import org.opencodemobile.shared.domain.event.EventTransport
import org.opencodemobile.shared.domain.event.RawServerEvent
import org.opencodemobile.shared.domain.event.RealtimeSnapshot
import org.opencodemobile.shared.domain.event.SessionSnapshot
import org.opencodemobile.shared.domain.event.SessionStatus

/** Thrown when the realtime HTTP surface answers with an unexpected status. */
public class RealtimeTransportException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * The Ktor implementation of [EventTransport] (`docs/ARCHITECTURE.md` §3.2).
 *
 * It is the only place that turns the OpenCode Server v2 `/api` HTTP surface into
 * the transport the realtime pipeline drives:
 *
 * - `GET /api/session` (cursor pages, read to the end) + `GET /api/session/active`
 *   for the (re)connect snapshot,
 * - `GET /api/session/active` for the polling fallback,
 * - `GET /api/event` for the SSE stream, read progressively with [readUTF8Line].
 *
 * SSE framing: each event is a single `data: <json>` line followed by a blank line,
 * with no `event:` or `id:` field; the type and the `evt_…` id are inside the JSON
 * envelope. `: heartbeat` comments emit internal activity signals. `server.connected` only signals that
 * the stream is open and is not forwarded as a business event. The server ignores
 * `Last-Event-ID`: events sent while disconnected are lost, so a reconnect is a
 * resync by the caller ([org.opencodemobile.shared.realtime.EventProcessor] refetches
 * the snapshot on every attempt), never a replay.
 *
 * The SSE body is consumed as a raw byte channel rather than through the generated
 * client (which only models request/response routes), so events are delivered as they
 * arrive instead of after the body closes. The generated client is still the only
 * consumer of the generated DTOs (Rule R3): it is used for the JSON routes and this
 * class maps its DTOs to the JSON-free domain types.
 */
public class NetworkingRealtimeTransport(
    private val httpClient: HttpClient,
    private val baseUrl: String,
    private val authTokenProvider: () -> String? = { null },
) : EventTransport {

    private val api: OpenCodeApiClient = OpenCodeApiClient(
        baseUrl = baseUrl,
        httpClient = httpClient,
        authTokenProvider = authTokenProvider,
    )

    override suspend fun fetchSnapshot(): RealtimeSnapshot =
        RealtimeSnapshot(sessions = listAllSessions(), statuses = pollStatuses())

    /** `/api/session/active` lists only running sessions; an absent session is idle. */
    override suspend fun pollStatuses(): Map<String, SessionStatus> =
        api.getActiveSessions().data.mapValues { SessionStatus(type = "busy") }

    private suspend fun listAllSessions(): List<SessionSnapshot> {
        val sessions = mutableListOf<SessionSnapshot>()
        var cursor: String? = null
        do {
            val page = api.listSessions(cursor = cursor)
            sessions += page.data.map { it.toDomain() }
            val next = page.cursor.next
            // A server repeating the same cursor would loop forever.
            cursor = next?.takeIf { it != cursor && page.data.isNotEmpty() }
        } while (cursor != null)
        return sessions
    }

    override fun openEventStream(): Flow<RawServerEvent> = flow {
        httpClient.prepareGet("$baseUrl/api/event") {
            // OkHttp's native 10 s read timeout is shorter than the server's
            // 15 s heartbeat. Keep REST defaults; EventProcessor owns the 30 s
            // inactivity watchdog, including heartbeat activity, for this flow.
            if (HttpTimeoutCapability in httpClient.engine.supportedCapabilities) {
                timeout {
                    requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                    socketTimeoutMillis = 60_000
                }
            }
            authTokenProvider()?.let { token ->
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            header(HttpHeaders.Accept, "text/event-stream")
        }.execute { response ->
            if (response.status != HttpStatusCode.OK) {
                throw RealtimeTransportException(
                    "GET /api/event failed with HTTP ${response.status.value}",
                )
            }

            val channel = response.bodyAsChannel()
            try {
                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: break
                    parseSseLine(line)?.let { emit(it) }
                }
            } finally {
                channel.cancel(null)
            }
        }
    }

    private fun parseSseLine(line: String): RawServerEvent? {
        if (!line.startsWith("data:")) return if (line.isNotEmpty()) RawServerEvent.activity() else null
        val data = line.removePrefix("data:").removePrefix(" ")
        val envelope = runCatching { Json.parseToJsonElement(data) }.getOrNull() as? JsonObject ?: return null
        val type = (envelope["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        if (type == SERVER_CONNECTED) return RawServerEvent.opened()
        val id = (envelope["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return RawServerEvent(id = id, type = type, data = data)
    }

    private companion object {
        const val SERVER_CONNECTED = "server.connected"
    }
}

private fun ApiSession.toDomain(): SessionSnapshot = SessionSnapshot(
    id = id,
    title = title,
    directory = location.directory,
    updatedAt = time.updated.toLong(),
)
