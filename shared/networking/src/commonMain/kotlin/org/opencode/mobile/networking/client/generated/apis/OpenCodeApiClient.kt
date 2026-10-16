// ==============================================================================
// AUTO-GENERATED CODE - DO NOT MODIFY BY HAND
//
// Generated from OpenCode Server v2 OpenAPI Specification (opencode 2.0.6 HttpApi, OpenAPI 3.1.0)
// Architectural Rules:
// - Rule R12: Generated code is never manually edited.
// - Rule R3: Generated types must NOT be imported outside shared/networking.
// ==============================================================================

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
