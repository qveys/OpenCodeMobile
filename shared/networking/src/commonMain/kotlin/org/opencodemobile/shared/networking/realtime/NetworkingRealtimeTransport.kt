package org.opencodemobile.shared.networking.realtime

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.opencode.mobile.networking.client.generated.apis.OpenCodeApiClient
import org.opencode.mobile.networking.client.generated.models.ApiSession
import org.opencode.mobile.networking.client.generated.models.ApiSessionStatus
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
 * It is the only place that turns the OpenCode Server v2 HTTP surface into the
 * transport the realtime pipeline drives:
 *
 * - `GET /session` + `GET /session/status` for the (re)connect snapshot,
 * - `GET /session/status` for the polling fallback,
 * - `GET /event` for the SSE stream, read progressively with [readUTF8Line].
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

    override suspend fun fetchSnapshot(): RealtimeSnapshot {
        val sessions = api.listSessions().map { it.toDomain() }
        val statuses = api.getSessionStatus().mapValues { (_, status) -> status.toDomain() }
        return RealtimeSnapshot(sessions = sessions, statuses = statuses)
    }

    override suspend fun pollStatuses(): Map<String, SessionStatus> =
        api.getSessionStatus().mapValues { (_, status) -> status.toDomain() }

    // The SSE field parser is a flat `when` over the RFC event fields; splitting it
    // would scatter the id/type/data accumulator state.
    @Suppress("CyclomaticComplexMethod")
    override fun openEventStream(): Flow<RawServerEvent> = flow {
        val response = httpClient.prepareGet("$baseUrl/event") {
            authTokenProvider()?.let { token ->
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            header(HttpHeaders.Accept, "text/event-stream")
        }.execute()

        if (response.status != HttpStatusCode.OK) {
            throw RealtimeTransportException(
                "GET /event failed with HTTP ${response.status.value}",
            )
        }

        val channel = response.bodyAsChannel()
        var id: String? = null
        var type: String? = null
        val dataLines = mutableListOf<String>()

        while (!channel.isClosedForRead) {
            val line = channel.readUTF8Line() ?: break
            when {
                line.isEmpty() -> {
                    if (id != null || type != null || dataLines.isNotEmpty()) {
                        emit(
                            RawServerEvent(
                                id = id,
                                type = type ?: "message",
                                data = dataLines.joinToString("\n"),
                            ),
                        )
                        id = null
                        type = null
                        dataLines.clear()
                    }
                }

                line.startsWith(":") -> Unit // SSE comment / keep-alive
                line.startsWith("id:") -> id = line.removePrefix("id:").trim().ifBlank { null }
                line.startsWith("event:") -> type = line.removePrefix("event:").trim()
                line.startsWith("data:") -> dataLines += line.removePrefix("data:").removePrefix(" ")
            }
        }
    }
}

private fun ApiSession.toDomain(): SessionSnapshot = SessionSnapshot(
    id = id,
    title = title,
    directory = directory,
    updatedAt = time?.updated,
)

private fun ApiSessionStatus.toDomain(): SessionStatus = SessionStatus(
    type = type,
    attempt = attempt,
    message = message,
    next = next,
)
