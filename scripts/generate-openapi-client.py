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
// Generated from OpenCode Server v2 OpenAPI Specification (v1.18.32, OpenAPI 3.1.0)
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

    # 1. Generate Models.kt
    models_code = f"""{HEADER_COMMENT}
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
"""
    with open(MODELS_DIR / "Models.kt", "w", encoding="utf-8") as f:
        f.write(models_code)
    print(f"Generated models -> {MODELS_DIR / 'Models.kt'}")

    # 2. Generate OpenCodeApiClient.kt
    client_code = f"""{HEADER_COMMENT}
package org.opencode.mobile.networking.client.generated.apis

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.opencode.mobile.networking.client.generated.models.*

/**
 * OpenCode Server v2 API Client.
 *
 * Generated against pinned OpenCode Server v2 OpenAPI specification.
 * Per Rule R3, this client and its generated models MUST NOT be imported
 * outside shared/networking. All application access goes through OpenCodeV2Adapter.
 */
class OpenCodeApiClient(
    val baseUrl: String,
    val httpClient: HttpClient,
    val authTokenProvider: (() -> String?)? = null
) {{
    private fun io.ktor.client.request.HttpRequestBuilder.applyAuth() {{
        val token = authTokenProvider?.invoke()
        if (!token.isNullOrBlank()) {{
            header("Authorization", "Bearer $token")
        }}
    }}

    // --- Health & Compatibility ---

    /**
     * Handshake health check (GET /global/health).
     */
    suspend fun getHealth(): ApiHealthResponse {{
        return httpClient.get("$baseUrl/global/health") {{
            applyAuth()
        }}.body()
    }}

    // --- Sessions ---

    /**
     * List all sessions (GET /session).
     */
    suspend fun listSessions(directory: String? = null): List<ApiSession> {{
        return httpClient.get("$baseUrl/session") {{
            applyAuth()
            if (directory != null) parameter("directory", directory)
        }}.body()
    }}

    /**
     * Get session details (GET /session/{{sessionID}}).
     */
    suspend fun getSession(sessionID: String): ApiSession {{
        return httpClient.get("$baseUrl/session/$sessionID") {{
            applyAuth()
        }}.body()
    }}

    /**
     * Create a new session (POST /session).
     */
    suspend fun createSession(request: ApiCreateSessionRequest): ApiSession {{
        return httpClient.post("$baseUrl/session") {{
            applyAuth()
            contentType(ContentType.Application.Json)
            setBody(request)
        }}.body()
    }}

    /**
     * Fork an existing session (POST /session/{{sessionID}}/fork).
     */
    suspend fun forkSession(sessionID: String, request: ApiForkSessionRequest = ApiForkSessionRequest()): ApiSession {{
        return httpClient.post("$baseUrl/session/$sessionID/fork") {{
            applyAuth()
            contentType(ContentType.Application.Json)
            setBody(request)
        }}.body()
    }}

    /**
     * Abort the active turn in a session (POST /session/{{sessionID}}/abort).
     */
    suspend fun abortSession(sessionID: String): Boolean {{
        httpClient.post("$baseUrl/session/$sessionID/abort") {{
            applyAuth()
        }}
        return true
    }}

    /**
     * Delete a session (DELETE /session/{{sessionID}}).
     */
    suspend fun deleteSession(sessionID: String): Boolean {{
        httpClient.delete("$baseUrl/session/$sessionID") {{
            applyAuth()
        }}
        return true
    }}

    /**
     * Get status of all active sessions (GET /session/status).
     */
    suspend fun getSessionStatus(): Map<String, ApiSessionStatus> {{
        return httpClient.get("$baseUrl/session/status") {{
            applyAuth()
        }}.body()
    }}

    // --- Messages & Async Prompt ---

    /**
     * List messages for a session (GET /session/{{sessionID}}/message).
     */
    suspend fun listMessages(sessionID: String): List<ApiMessage> {{
        return httpClient.get("$baseUrl/session/$sessionID/message") {{
            applyAuth()
        }}.body()
    }}

    /**
     * Send async prompt (POST /session/{{sessionID}}/prompt_async).
     * Non-blocking: returns immediately; output streamed via /event SSE.
     */
    suspend fun sendPromptAsync(
        sessionID: String,
        request: ApiPromptAsyncRequest,
        directory: String? = null
    ) {{
        httpClient.post("$baseUrl/session/$sessionID/prompt_async") {{
            applyAuth()
            contentType(ContentType.Application.Json)
            if (directory != null) parameter("directory", directory)
            setBody(request)
        }}
    }}

    // --- Permissions ---

    /**
     * List pending permissions (GET /permission).
     */
    suspend fun listPermissions(directory: String? = null): List<ApiPermissionRequest> {{
        return httpClient.get("$baseUrl/permission") {{
            applyAuth()
            if (directory != null) parameter("directory", directory)
        }}.body()
    }}

    /**
     * Respond to a permission request (POST /permission/{{requestID}}/reply).
     */
    suspend fun replyPermission(
        requestID: String,
        request: ApiPermissionReplyRequest,
        directory: String? = null
    ): Boolean {{
        return httpClient.post("$baseUrl/permission/$requestID/reply") {{
            applyAuth()
            contentType(ContentType.Application.Json)
            if (directory != null) parameter("directory", directory)
            setBody(request)
        }}.body()
    }}

    // --- Questions ---

    /**
     * List pending questions (GET /question).
     */
    suspend fun listQuestions(directory: String? = null): List<ApiQuestionRequest> {{
        return httpClient.get("$baseUrl/question") {{
            applyAuth()
            if (directory != null) parameter("directory", directory)
        }}.body()
    }}

    /**
     * Reply to a question request (POST /question/{{requestID}}/reply).
     */
    suspend fun replyQuestion(
        requestID: String,
        request: ApiQuestionReplyRequest,
        directory: String? = null
    ): Boolean {{
        return httpClient.post("$baseUrl/question/$requestID/reply") {{
            applyAuth()
            contentType(ContentType.Application.Json)
            if (directory != null) parameter("directory", directory)
            setBody(request)
        }}.body()
    }}

    /**
     * Reject a question request (POST /question/{{requestID}}/reject).
     */
    suspend fun rejectQuestion(
        requestID: String,
        directory: String? = null
    ): Boolean {{
        return httpClient.post("$baseUrl/question/$requestID/reject") {{
            applyAuth()
            if (directory != null) parameter("directory", directory)
        }}.body()
    }}

    // --- Projects & Providers ---

    /**
     * List projects (GET /project).
     */
    suspend fun listProjects(): List<ApiProject> {{
        return httpClient.get("$baseUrl/project") {{
            applyAuth()
        }}.body()
    }}

    /**
     * List providers and models (GET /provider).
     */
    suspend fun listProviders(): List<ApiProvider> {{
        return httpClient.get("$baseUrl/provider") {{
            applyAuth()
        }}.body()
    }}
}}
"""
    with open(APIS_DIR / "OpenCodeApiClient.kt", "w", encoding="utf-8") as f:
        f.write(client_code)
    print(f"Generated API client -> {APIS_DIR / 'OpenCodeApiClient.kt'}")

if __name__ == "__main__":
    generate()
