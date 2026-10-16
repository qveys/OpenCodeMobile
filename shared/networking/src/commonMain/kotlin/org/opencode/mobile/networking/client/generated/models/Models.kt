// ==============================================================================
// AUTO-GENERATED CODE - DO NOT MODIFY BY HAND
//
// Generated from OpenCode Server v2 OpenAPI Specification (opencode 2.0.6 HttpApi, OpenAPI 3.1.0)
// Architectural Rules:
// - Rule R12: Generated code is never manually edited.
// - Rule R3: Generated types must NOT be imported outside shared/networking.
// ==============================================================================

package org.opencode.mobile.networking.client.generated.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement

// ApiPromptRequest has no agent/model: the v2.0.6 spec's prompt body only defines
// id, text, files, agents, skills, metadata, delivery, resume. Agent/model are set on the session.
// Shapes follow packages/protocol/openapi.json (opencode 2.0.6). Unions and
// deep structures the app does not interpret are kept as JsonElement.

@Serializable
data class ApiServerInfo(
    val version: String,
    val pid: Int,
    val urls: List<String>,
    val paths: ApiServerPaths
)

@Serializable
data class ApiServerPaths(val tmp: String)

@Serializable
data class ApiLocation(
    val directory: String
)

@Serializable
data class ApiCursor(
    val previous: String? = null,
    val next: String? = null
)

@Serializable
data class ApiModelRef(
    val id: String,
    val providerID: String,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val variant: String? = null
)

@Serializable
data class ApiSessionTime(
    val created: Double,
    val updated: Double
)

@Serializable
data class ApiSession(
    val id: String,
    val parentID: String? = null,
    val projectID: String,
    val agent: String? = null,
    val model: ApiModelRef? = null,
    val title: String? = null,
    val subpath: String? = null,
    val time: ApiSessionTime,
    val location: ApiLocation
)

@Serializable
data class ApiSessionResponse(
    val data: ApiSession
)

@Serializable
data class ApiSessionsResponse(
    val data: List<ApiSession>,
    val cursor: ApiCursor
)

@Serializable
data class ApiCreateSessionRequest(
    val id: String? = null,
    val title: String? = null,
    val agent: String? = null,
    val model: ApiModelRef? = null,
    val location: ApiLocation? = null
)

// PATCH /api/session/{sessionID}: only `title` is exposed (spec also has `permissions`).
@Serializable
data class ApiUpdateSessionRequest(
    val title: String? = null
)

@Serializable
data class ApiSwitchSessionAgentRequest(val agent: String)

@Serializable
data class ApiSwitchSessionModelRequest(val model: ApiModelRef)

@Serializable
data class ApiForkSessionRequest(
    val before: String? = null
)

@Serializable
data class ApiInterruptResponse(
    val interrupted: Boolean
)

@Serializable
// REST active sessions are running only; idle/busy/retry belong to SSE session.status.
data class ApiSessionActive(
    val type: String
) {
    init { require(type == "running") { "SessionActive.type must be running" } }
}

@Serializable
data class ApiActiveSessionsResponse(
    val data: Map<String, ApiSessionActive>
)

@Serializable
data class ApiMessageTime(
    val created: Double,
    val completed: Double? = null
)

@Serializable
data class ApiMessageContent(
    val type: String,
    val text: String? = null,
    val id: String? = null,
    val name: String? = null,
    val state: JsonElement? = null
)

// Session.Message.Shell `output` (cursor/size/truncated omitted: not read by the app).
@Serializable
data class ApiShellOutput(
    val output: String
)

// Discriminated by `type` (user, assistant, shell, ...); only the fields the app reads.
@Serializable
data class ApiMessage(
    val id: String,
    val type: String,
    val time: ApiMessageTime,
    val text: String? = null,
    val agent: String? = null,
    val model: ApiModelRef? = null,
    val content: List<ApiMessageContent> = emptyList(),
    val finish: String? = null,
    val error: JsonElement? = null,
    val command: String? = null,
    val output: ApiShellOutput? = null
)

@Serializable
data class ApiMessagesResponse(
    val data: List<ApiMessage>,
    val cursor: ApiCursor
)

@Serializable
data class ApiPromptRequest(
    val id: String? = null,
    val text: String,
    val delivery: String? = null,
    val resume: Boolean? = null
)

@Serializable
data class ApiPromptResponse(
    val data: JsonObject
)

@Serializable
data class ApiPermissionRequest(
    val id: String,
    val sessionID: String,
    val action: String,
    val resources: List<String>,
    val save: List<String> = emptyList(),
    val metadata: JsonElement? = null,
    val source: JsonElement? = null,
    val message: String? = null
)

@Serializable
data class ApiPermissionListResponse(
    val location: ApiLocation? = null,
    val data: List<ApiPermissionRequest>
)

@Serializable
data class ApiPermissionReplyRequest(
    val decision: String, // "once", "always", "reject"
    val message: String? = null
)

@Serializable
data class ApiForm(
    val id: String,
    val sessionID: String,
    val title: String,
    val metadata: JsonElement? = null,
    val fields: List<JsonElement>
) {
    init { require(fields.isNotEmpty()) { "Form.fields must contain at least one field" } }
}

@Serializable
data class ApiFormListResponse(
    val location: ApiLocation? = null,
    val data: List<ApiForm>
)

@Serializable
data class ApiFormReplyRequest(
    val answer: JsonElement
)

@Serializable
data class ApiProject(
    val id: String,
    val canonical: String,
    val name: String? = null,
    val sandboxes: List<String>
)

@Serializable
data class ApiProviderInfo(
    val id: String,
    val name: String,
    val activation: String
)

@Serializable
data class ApiProviderListResponse(
    val location: ApiLocation,
    val data: List<ApiProviderInfo>
)

@Serializable
data class ApiAgentInfo(
    val id: String,
    val name: String,
    val model: ApiModelRef? = null,
    val description: String? = null,
    val mode: String,
    val hidden: Boolean
)

@Serializable
data class ApiAgentListResponse(
    val location: ApiLocation,
    val data: List<ApiAgentInfo>
)

@Serializable
data class ApiModelInfo(
    val id: String,
    val modelID: String,
    val providerID: String,
    val name: String,
    val enabled: Boolean,
    val status: String
)

@Serializable
data class ApiModelListResponse(
    val location: ApiLocation,
    val data: List<ApiModelInfo>
)

// SSE envelope on GET /api/event: `type` e.g. "server.connected", payload in `data`.
@Serializable
data class ApiEvent(
    val id: String,
    val type: String,
    val created: Double? = null,
    val metadata: JsonElement? = null,
    val location: ApiLocation? = null,
    val data: JsonElement? = null
)
