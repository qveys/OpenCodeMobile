#!/usr/bin/env python3
"""
OpenCode Server v2 OpenAPI Client Generator for Kotlin Multiplatform.
Generates typed Kotlin data classes and Ktor-based API client into shared/networking.
Rules:
- Rule R12: Generated code is never manually edited.
- Rule R3: Generated types live exclusively in shared/networking.
"""

import json
import os
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
SPEC_PATH = REPO_ROOT / "shared" / "networking" / "openapi" / "opencode-server-v2.json"
OUT_DIR = REPO_ROOT / "shared" / "networking" / "src" / "commonMain" / "kotlin" / "org" / "opencode" / "mobile" / "networking" / "client" / "generated"

MODELS_DIR = OUT_DIR / "models"
APIS_DIR = OUT_DIR / "apis"

HEADER_COMMENT = """// ==============================================================================
// AUTO-GENERATED CODE - DO NOT MODIFY BY HAND
//
// Generated from OpenCode Server v2 OpenAPI Specification (opencode 2.0.6 HttpApi, OpenAPI 3.1.0)
// Architectural Rules:
// - Rule R12: Generated code is never manually edited.
// - Rule R3: Generated types must NOT be imported outside shared/networking.
// ==============================================================================
"""

def generate():
    if not SPEC_PATH.exists():
        print(f"Error: OpenAPI spec not found at {SPEC_PATH}", file=sys.stderr)
        sys.exit(1)

    with open(SPEC_PATH, "r", encoding="utf-8") as f:
        spec = json.load(f)

    MODELS_DIR.mkdir(parents=True, exist_ok=True)
    APIS_DIR.mkdir(parents=True, exist_ok=True)

    print(f"Loaded OpenAPI spec: {spec.get('info', {}).get('title')} v{spec.get('info', {}).get('version')}")
    # Guard: every route emitted below must exist in the pinned spec.
    used = [
        ("get", "/api/info"), ("get", "/api/session"), ("post", "/api/session"),
        ("get", "/api/session/{sessionID}"), ("delete", "/api/session/{sessionID}"),
        ("patch", "/api/session/{sessionID}"), ("post", "/api/session/{sessionID}/fork"), ("post", "/api/session/{sessionID}/interrupt"),
        ("post", "/api/session/{sessionID}/agent"), ("post", "/api/session/{sessionID}/model"),
        ("get", "/api/session/active"), ("get", "/api/session/{sessionID}/message"),
        ("post", "/api/session/{sessionID}/prompt"), ("get", "/api/permission/request"),
        ("get", "/api/session/{sessionID}/permission"),
        ("post", "/api/session/{sessionID}/permission/{requestID}/reply"),
        ("get", "/api/form"), ("get", "/api/session/{sessionID}/form"),
        ("post", "/api/session/{sessionID}/form/{formID}/reply"),
        ("delete", "/api/session/{sessionID}/form/{formID}"), ("get", "/api/project"),
        ("get", "/api/provider"), ("get", "/api/agent"), ("get", "/api/model"),
    ]
    missing = [f"{m.upper()} {p}" for m, p in used if m not in spec.get("paths", {}).get(p, {})]
    if missing:
        print(f"Error: routes missing from spec: {missing}", file=sys.stderr)
        sys.exit(1)

    # 1. Generate Models.kt
    models_code = HEADER_COMMENT + """
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
    val description: String? = null,
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
"""
    with open(MODELS_DIR / "Models.kt", "w", encoding="utf-8") as f:
        f.write(models_code)
    print(f"Generated models -> {MODELS_DIR / 'Models.kt'}")

    # 2. Generate OpenCodeApiClient.kt
    client_code = HEADER_COMMENT + """
package org.opencode.mobile.networking.client.generated.apis

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.opencode.mobile.networking.client.generated.models.*

/**
 * OpenCode Server v2 API Client (HttpApi surface under /api).
 *
 * Generated against pinned OpenCode Server v2 OpenAPI specification.
 * Per Rule R3, this client and its generated models MUST NOT be imported
 * outside shared/networking. All application access goes through OpenCodeV2Adapter.
 *
 * Directory scoping: the server resolves the working directory from the request
 * location (`directory` query parameter on list routes, `location` on create).
 */
class OpenCodeApiClient(
    val baseUrl: String,
    val httpClient: HttpClient,
    val authTokenProvider: (() -> String?)? = null
) {
    private fun io.ktor.client.request.HttpRequestBuilder.applyAuth() {
        val token = authTokenProvider?.invoke()
        if (!token.isNullOrBlank()) {
            header("Authorization", "Bearer $token")
        }
    }

    // --- Server info & Compatibility ---

    /**
     * Handshake probe and version source (GET /api/info). There is no /api/health.
     */
    suspend fun getInfo(): ApiServerInfo {
        return httpClient.get("$baseUrl/api/info") {
            applyAuth()
        }.body()
    }

    // --- Sessions ---

    /**
     * List sessions (GET /api/session).
     */
    suspend fun listSessions(
        directory: String? = null,
        limit: Int? = null,
        cursor: String? = null
    ): ApiSessionsResponse {
        return httpClient.get("$baseUrl/api/session") {
            applyAuth()
            if (directory != null) parameter("directory", directory)
            if (limit != null) parameter("limit", limit)
            if (cursor != null) parameter("cursor", cursor)
        }.body()
    }

    /**
     * Get session details (GET /api/session/{sessionID}).
     */
    suspend fun getSession(sessionID: String): ApiSessionResponse {
        return httpClient.get("$baseUrl/api/session/$sessionID") {
            applyAuth()
        }.body()
    }

    /**
     * Create a new session (POST /api/session).
     */
    suspend fun createSession(request: ApiCreateSessionRequest = ApiCreateSessionRequest()): ApiSessionResponse {
        return httpClient.post("$baseUrl/api/session") {
            applyAuth()
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
    }

    /**
     * Fork an existing session (POST /api/session/{sessionID}/fork).
     */
    suspend fun forkSession(sessionID: String, request: ApiForkSessionRequest = ApiForkSessionRequest()): ApiSessionResponse {
        return httpClient.post("$baseUrl/api/session/$sessionID/fork") {
            applyAuth()
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
    }

    /**
     * Interrupt the active turn in a session (POST /api/session/{sessionID}/interrupt).
     */
    suspend fun interruptSession(sessionID: String): ApiInterruptResponse {
        return httpClient.post("$baseUrl/api/session/$sessionID/interrupt") {
            applyAuth()
        }.body()
    }

    /**
     * Update mutable session properties, e.g. rename (PATCH /api/session/{sessionID}, 204).
     */
    suspend fun updateSession(sessionID: String, request: ApiUpdateSessionRequest) {
        httpClient.patch("$baseUrl/api/session/$sessionID") {
            applyAuth()
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    /** Switch the session agent for subsequent turns (204). */
    suspend fun switchSessionAgent(sessionID: String, request: ApiSwitchSessionAgentRequest) {
        httpClient.post("$baseUrl/api/session/$sessionID/agent") {
            applyAuth()
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    /** Switch the session model for subsequent turns (204). */
    suspend fun switchSessionModel(sessionID: String, request: ApiSwitchSessionModelRequest) {
        httpClient.post("$baseUrl/api/session/$sessionID/model") {
            applyAuth()
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    /**
     * Delete a session (DELETE /api/session/{sessionID}, 204).
     */
    suspend fun deleteSession(sessionID: String) {
        httpClient.delete("$baseUrl/api/session/$sessionID") {
            applyAuth()
        }
    }

    /**
     * Running sessions keyed by session id (GET /api/session/active).
     */
    suspend fun getActiveSessions(): ApiActiveSessionsResponse {
        return httpClient.get("$baseUrl/api/session/active") {
            applyAuth()
        }.body()
    }

    // --- Messages & Prompt ---

    /**
     * List messages for a session (GET /api/session/{sessionID}/message).
     */
    suspend fun listMessages(
        sessionID: String,
        limit: Int? = null,
        cursor: String? = null
    ): ApiMessagesResponse {
        return httpClient.get("$baseUrl/api/session/$sessionID/message") {
            applyAuth()
            if (limit != null) parameter("limit", limit)
            if (cursor != null) parameter("cursor", cursor)
        }.body()
    }

    /**
     * Send a prompt (POST /api/session/{sessionID}/prompt).
     * Returns once the message is queued; output streams via GET /api/event.
     */
    suspend fun sendPrompt(
        sessionID: String,
        request: ApiPromptRequest
    ): ApiPromptResponse {
        return httpClient.post("$baseUrl/api/session/$sessionID/prompt") {
            applyAuth()
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
    }

    // --- Permissions ---

    /**
     * Pending permission requests across the location (GET /api/permission/request).
     */
    suspend fun listPermissionRequests(): ApiPermissionListResponse {
        return httpClient.get("$baseUrl/api/permission/request") {
            applyAuth()
        }.body()
    }

    /**
     * Pending permission requests of one session (GET /api/session/{sessionID}/permission).
     */
    suspend fun listSessionPermissions(sessionID: String): ApiPermissionListResponse {
        return httpClient.get("$baseUrl/api/session/$sessionID/permission") {
            applyAuth()
        }.body()
    }

    /**
     * Respond to a permission request (POST /api/session/{sessionID}/permission/{requestID}/reply, 204).
     */
    suspend fun replyPermission(
        sessionID: String,
        requestID: String,
        request: ApiPermissionReplyRequest
    ) {
        httpClient.post("$baseUrl/api/session/$sessionID/permission/$requestID/reply") {
            applyAuth()
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    // --- Forms (replace the legacy question flow) ---

    /**
     * Pending forms across the location (GET /api/form).
     */
    suspend fun listForms(): ApiFormListResponse {
        return httpClient.get("$baseUrl/api/form") {
            applyAuth()
        }.body()
    }

    /**
     * Pending forms of one session (GET /api/session/{sessionID}/form).
     */
    suspend fun listSessionForms(sessionID: String): ApiFormListResponse {
        return httpClient.get("$baseUrl/api/session/$sessionID/form") {
            applyAuth()
        }.body()
    }

    /**
     * Answer a form (POST /api/session/{sessionID}/form/{formID}/reply, 204).
     */
    suspend fun replyForm(
        sessionID: String,
        formID: String,
        request: ApiFormReplyRequest
    ) {
        httpClient.post("$baseUrl/api/session/$sessionID/form/$formID/reply") {
            applyAuth()
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    /**
     * Cancel a form (DELETE /api/session/{sessionID}/form/{formID}, 204).
     */
    suspend fun cancelForm(sessionID: String, formID: String) {
        httpClient.delete("$baseUrl/api/session/$sessionID/form/$formID") {
            applyAuth()
        }
    }

    // --- Projects, Providers, Agents, Models ---

    /**
     * List projects (GET /api/project).
     */
    suspend fun listProjects(): List<ApiProject> {
        return httpClient.get("$baseUrl/api/project") {
            applyAuth()
        }.body()
    }

    /**
     * List providers (GET /api/provider).
     */
    suspend fun listProviders(): ApiProviderListResponse {
        return httpClient.get("$baseUrl/api/provider") {
            applyAuth()
        }.body()
    }

    /**
     * List agents (GET /api/agent).
     */
    suspend fun listAgents(): ApiAgentListResponse {
        return httpClient.get("$baseUrl/api/agent") {
            applyAuth()
        }.body()
    }

    /**
     * List models (GET /api/model).
     */
    suspend fun listModels(): ApiModelListResponse {
        return httpClient.get("$baseUrl/api/model") {
            applyAuth()
        }.body()
    }
}
"""
    with open(APIS_DIR / "OpenCodeApiClient.kt", "w", encoding="utf-8") as f:
        f.write(client_code)
    print(f"Generated API client -> {APIS_DIR / 'OpenCodeApiClient.kt'}")

if __name__ == "__main__":
    generate()
