package org.opencodemobile.shared.networking.permission

import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.opencode.mobile.networking.client.generated.apis.OpenCodeApiClient
import org.opencode.mobile.networking.client.generated.models.ApiPermissionReplyRequest
import org.opencode.mobile.networking.client.generated.models.ApiPermissionRequest
import org.opencodemobile.shared.domain.permission.PermissionCapabilities
import org.opencodemobile.shared.domain.permission.PermissionDecision
import org.opencodemobile.shared.domain.permission.PermissionEvent
import org.opencodemobile.shared.domain.permission.PermissionEventDecoder
import org.opencodemobile.shared.domain.permission.PermissionPort
import org.opencodemobile.shared.domain.permission.PermissionReplyOutcome
import org.opencodemobile.shared.domain.permission.PermissionRequest

/**
 * The Ktor implementation of [PermissionPort] and [PermissionEventDecoder]
 * (`docs/ARCHITECTURE.md` §3.3, §"Permission approval confirmation").
 *
 * It is the only place that knows the server's permission vocabulary:
 * `GET /api/permission/request`, `POST /api/session/{sessionID}/permission/{requestID}/reply` with
 * `decision` (`once` / `reject` / `always`), and the `permission.asked` / `permission.replied` SSE
 * payloads. The domain and the UI only ever see [PermissionDecision] and [PermissionRequest].
 */
public class OpenCodePermissionGateway(
    private val httpClient: HttpClient,
    private val baseUrl: String,
    private val authTokenProvider: () -> String? = { null },
) : PermissionPort, PermissionEventDecoder {

    private val api: OpenCodeApiClient = OpenCodeApiClient(
        baseUrl = baseUrl,
        httpClient = httpClient,
        authTokenProvider = authTokenProvider,
    )

    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun pendingPermissions(): List<PermissionRequest> =
        api.listPermissionRequests().data.map { it.toDomain() }

    override suspend fun reply(
        sessionId: String,
        requestId: String,
        decision: PermissionDecision,
    ): PermissionReplyOutcome {
        api.replyPermission(
            sessionID = sessionId,
            requestID = requestId,
            request = ApiPermissionReplyRequest(
                decision = decision.toWireValue(),
            ),
        )
        return PermissionReplyOutcome.Accepted
    }

    override fun decode(type: String, payload: String): PermissionEvent? {
        val root = runCatching { json.parseToJsonElement(payload) }.getOrNull() as? JsonObject
            ?: return null
        val declaredType = root.stringOrNull("type") ?: type
        val data = extractDataObject(root) ?: return null
        return when (declaredType) {
            "permission.asked" -> decodeAsked(data)
            "permission.replied" -> decodeReplied(data)
            else -> null
        }
    }

    private fun extractDataObject(root: JsonObject): JsonObject? {
        val dataElement = root["data"] ?: root["properties"]
        if (dataElement != null) {
            return when (dataElement) {
                is JsonObject -> dataElement
                is JsonPrimitive -> {
                    if (dataElement.isString) {
                        runCatching { json.parseToJsonElement(dataElement.content) }.getOrNull() as? JsonObject
                    } else {
                        null
                    }
                }
                else -> null
            }
        }
        if (root.containsKey("action") || root.containsKey("resources") || root.containsKey("permission") ||
            root.containsKey("requestID") || root.containsKey("requestId") || root.containsKey("reply")
        ) {
            return root
        }
        return null
    }

    private fun decodeAsked(data: JsonObject): PermissionEvent? {
        val id = data.stringOrNull("id") ?: return null
        val sessionId = data.stringOrNull("sessionID")
            ?: data.stringOrNull("sessionId")
            ?: return null
        val tool = data.stringOrNull("action")
            ?: data.stringOrNull("permission")
            ?: return null
        val patterns = if (data.containsKey("resources")) {
            data.stringList("resources")
        } else {
            data.stringList("patterns")
        }
        val rememberScopes = if (data.containsKey("save")) {
            data.stringList("save")
        } else {
            data.stringList("always")
        }
        val rawArguments = data["metadata"]?.toString().orEmpty()
        return PermissionEvent.Asked(
            PermissionRequest(
                id = id,
                sessionId = sessionId,
                tool = tool,
                patterns = patterns,
                rawArguments = rawArguments,
                rememberScopes = rememberScopes,
                capabilities = PermissionCapabilities.fromServer(rememberScopes),
            ),
        )
    }

    private fun decodeReplied(data: JsonObject): PermissionEvent? {
        val requestId = data.stringOrNull("requestID")
            ?: data.stringOrNull("requestId")
            ?: return null
        return PermissionEvent.Replied(requestId)
    }

    private fun ApiPermissionRequest.toDomain(): PermissionRequest = PermissionRequest(
        id = id,
        sessionId = sessionID,
        tool = action,
        patterns = resources,
        rawArguments = metadata?.toString().orEmpty(),
        rememberScopes = save,
        capabilities = PermissionCapabilities.fromServer(save),
    )
}

/**
 * The app's decision, encoded exactly as the OpenCode Server v2 HTTP API
 * expects (`decision`: `once` / `always` / `reject`). The app's
 * vocabulary ("Allow once" / "Deny" / "Allow session") never leaks onto the wire,
 * and the wire never leaks into the UI.
 */
internal fun PermissionDecision.toWireValue(): String = when (this) {
    PermissionDecision.Once -> "once"
    PermissionDecision.Deny -> "reject"
    PermissionDecision.Remember -> "always"
}

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

/**
 * Reads a string array, ignoring non-primitive elements instead of throwing.
 * `PermissionEventDecoder.decode` is documented never to throw, and a malformed
 * element must not be able to kill the SSE pipeline.
 */
private fun JsonObject.stringList(key: String): List<String> =
    (this[key] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        ?: emptyList()
