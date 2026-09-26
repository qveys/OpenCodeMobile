// ==============================================================================
// AUTO-GENERATED CODE - DO NOT MODIFY BY HAND
//
// Generated from OpenCode Server v2 OpenAPI Specification (v1.18.32, OpenAPI 3.1.0)
// Architectural Rules:
// - Rule R12: Generated code is never manually edited.
// - Rule R3: Generated types must NOT be imported outside shared/networking.
// ==============================================================================

package org.opencode.mobile.networking.client.generated.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ApiHealthResponse(
    val healthy: Boolean,
    val version: String
)

@Serializable
data class ApiSessionTime(
    val created: Long,
    val updated: Long? = null
)

@Serializable
data class ApiSession(
    val id: String,
    val slug: String? = null,
    val projectID: String? = null,
    val workspaceID: String? = null,
    val directory: String? = null,
    val path: String? = null,
    val parentID: String? = null,
    val title: String? = null,
    val version: String? = null,
    val time: ApiSessionTime? = null
)

@Serializable
data class ApiCreateSessionRequest(
    val directory: String? = null,
    val title: String? = null,
    val parentID: String? = null
)

@Serializable
data class ApiForkSessionRequest(
    val messageID: String? = null
)

@Serializable
data class ApiSessionStatus(
    val type: String,
    val attempt: Int? = null,
    val message: String? = null,
    val next: Long? = null
)

@Serializable
data class ApiMessagePart(
    val id: String? = null,
    val type: String,
    val text: String? = null,
    val callID: String? = null,
    val tool: String? = null,
    val args: JsonElement? = null,
    val result: JsonElement? = null
)

@Serializable
data class ApiMessage(
    val id: String,
    val sessionID: String,
    val role: String,
    val time: ApiSessionTime? = null,
    val agent: String? = null,
    val model: String? = null,
    val parts: List<ApiMessagePart> = emptyList()
)

@Serializable
data class ApiModelRef(
    val providerID: String,
    val modelID: String
)

@Serializable
data class ApiPromptPartInput(
    val type: String = "text",
    val text: String? = null,
    val path: String? = null
)

@Serializable
data class ApiPromptAsyncRequest(
    val messageID: String? = null,
    val agent: String? = null,
    val model: ApiModelRef? = null,
    val parts: List<ApiPromptPartInput>
)

@Serializable
data class ApiPermissionRequest(
    val id: String,
    val sessionID: String,
    val permission: String,
    val patterns: List<String> = emptyList(),
    val always: List<String> = emptyList(),
    val tool: JsonElement? = null,
    val metadata: JsonElement? = null
)

@Serializable
data class ApiPermissionReplyRequest(
    val reply: String, // "once", "always", "reject"
    val message: String? = null
)

@Serializable
data class ApiQuestionOption(
    val label: String,
    val description: String? = null
)

@Serializable
data class ApiQuestionInfo(
    val question: String,
    val header: String,
    val options: List<ApiQuestionOption> = emptyList(),
    val multiple: Boolean = false,
    val custom: Boolean = false
)

@Serializable
data class ApiQuestionRequest(
    val id: String,
    val sessionID: String,
    val questions: List<ApiQuestionInfo> = emptyList(),
    val tool: JsonElement? = null
)

@Serializable
data class ApiQuestionReplyRequest(
    val answers: List<List<String>>
)

@Serializable
data class ApiProject(
    val id: String,
    val worktree: String? = null,
    val name: String? = null
)

@Serializable
data class ApiModelInfo(
    val id: String,
    val name: String? = null,
    val providerID: String? = null
)

@Serializable
data class ApiProvider(
    val id: String,
    val name: String? = null,
    val models: List<ApiModelInfo> = emptyList()
)

@Serializable
data class ApiEvent(
    val id: String,
    val type: String,
    val properties: JsonElement? = null
)
