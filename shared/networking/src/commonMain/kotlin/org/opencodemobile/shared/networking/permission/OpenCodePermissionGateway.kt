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
 * `GET /permission`, `POST /permission/{requestID}/reply` with `once` / `reject`
 * / `always`, and the `permission.asked` / `permission.replied` SSE payloads. The
 * domain and the UI only ever see [PermissionDecision] and [PermissionRequest].
 *
 * **Protocol scope.** The app standardizes on the canonical root surface
 * (ADR-0002 §3.1). The experimental `permission.v2.*` events and the
 * `/api/session/{sessionID}/permission/...` reply route are a different protocol
 * whose reply returns `204` and has no `once`/`always`/`reject` body; this class
 * does not silently conflate the two. `permission.v2.*` is deliberately ignored
 * until it is implemented on its own (rather than surfacing a v2 request that
 * `GET /permission` can never reconcile or reply to).
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
        api.listPermissions().map { it.toDomain() }

    override suspend fun reply(
        requestId: String,
        decision: PermissionDecision,
    ): PermissionReplyOutcome {
        val accepted = api.replyPermission(
            requestID = requestId,
            request = ApiPermissionReplyRequest(reply = decision.toWireValue()),
        )
        return if (accepted) {
            PermissionReplyOutcome.Accepted
        } else {
            PermissionReplyOutcome.Rejected("the server did not accept the decision")
        }
    }

    override fun decode(type: String, payload: String): PermissionEvent? {
        val root = runCatching { json.parseToJsonElement(payload) }.getOrNull() as? JsonObject
            ?: return null
        val declaredType = root.stringOrNull("type") ?: type
        return when (declaredType) {
            "permission.asked" -> decodeAsked(root)
            "permission.replied" -> decodeReplied(root)
            // Different protocol, see the class doc: ignored, not conflated.
            "permission.v2.asked", "permission.v2.replied" -> null
            else -> null
        }
    }

    private fun decodeAsked(root: JsonObject): PermissionEvent? {
        val properties = root["properties"] as? JsonObject ?: return null
        val id = properties.stringOrNull("id") ?: return null
        val tool = properties.stringOrNull("permission") ?: return null
        val rememberScopes = properties.stringList("always")
        return PermissionEvent.Asked(
            PermissionRequest(
                id = id,
                sessionId = properties.stringOrNull("sessionID"),
                tool = tool,
                patterns = properties.stringList("patterns"),
                rawArguments = properties["metadata"]?.toString().orEmpty(),
                rememberScopes = rememberScopes,
                capabilities = PermissionCapabilities.fromServer(rememberScopes),
            ),
        )
    }

    private fun decodeReplied(root: JsonObject): PermissionEvent? {
        val properties = root["properties"] as? JsonObject ?: return null
        val requestId = properties.stringOrNull("requestID")
            ?: properties.stringOrNull("requestId")
            ?: return null
        return PermissionEvent.Replied(requestId)
    }

    private fun ApiPermissionRequest.toDomain(): PermissionRequest = PermissionRequest(
        id = id,
        sessionId = sessionID,
        tool = permission,
        patterns = patterns,
        rawArguments = metadata?.toString().orEmpty(),
        rememberScopes = always,
        capabilities = PermissionCapabilities.fromServer(always),
    )
}

/**
 * The app's decision, encoded exactly as the pinned OpenCode Server v2 root
 * surface expects (`PermissionV2Reply`: `once` / `always` / `reject`). The app's
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
