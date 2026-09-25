// ==============================================================================
// AUTO-GENERATED CODE - DO NOT MODIFY BY HAND
//
// Generated from OpenCode Server v2 OpenAPI Specification (v1.18.32, OpenAPI 3.1.0)
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
) {
    private fun io.ktor.client.request.HttpRequestBuilder.applyAuth() {
        val token = authTokenProvider?.invoke()
        if (!token.isNullOrBlank()) {
            header("Authorization", "Bearer $token")
        }
    }

    // --- Health & Compatibility ---

    /**
     * Handshake health check (GET /global/health).
     */
    suspend fun getHealth(): ApiHealthResponse {
        return httpClient.get("$baseUrl/global/health") {
            applyAuth()
        }.body()
    }

    // --- Sessions ---

    /**
     * List all sessions (GET /session).
     */
    suspend fun listSessions(directory: String? = null): List<ApiSession> {
        return httpClient.get("$baseUrl/session") {
            applyAuth()
            if (directory != null) parameter("directory", directory)
        }.body()
    }

    /**
     * Get session details (GET /session/{sessionID}).
     */
    suspend fun getSession(sessionID: String): ApiSession {
        return httpClient.get("$baseUrl/session/$sessionID") {
            applyAuth()
        }.body()
    }

    /**
     * Create a new session (POST /session).
     */
    suspend fun createSession(request: ApiCreateSessionRequest): ApiSession {
        return httpClient.post("$baseUrl/session") {
            applyAuth()
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
    }

    /**
     * Fork an existing session (POST /session/{sessionID}/fork).
     */
    suspend fun forkSession(sessionID: String, request: ApiForkSessionRequest = ApiForkSessionRequest()): ApiSession {
        return httpClient.post("$baseUrl/session/$sessionID/fork") {
            applyAuth()
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
    }

    /**
     * Abort the active turn in a session (POST /session/{sessionID}/abort).
     */
    suspend fun abortSession(sessionID: String): Boolean {
        httpClient.post("$baseUrl/session/$sessionID/abort") {
            applyAuth()
        }
        return true
    }

    /**
     * Delete a session (DELETE /session/{sessionID}).
     */
    suspend fun deleteSession(sessionID: String): Boolean {
        httpClient.delete("$baseUrl/session/$sessionID") {
            applyAuth()
        }
        return true
    }

    /**
     * Get status of all active sessions (GET /session/status).
     */
    suspend fun getSessionStatus(): Map<String, ApiSessionStatus> {
        return httpClient.get("$baseUrl/session/status") {
            applyAuth()
        }.body()
    }

    // --- Messages & Async Prompt ---

    /**
     * List messages for a session (GET /session/{sessionID}/message).
     */
    suspend fun listMessages(sessionID: String): List<ApiMessage> {
        return httpClient.get("$baseUrl/session/$sessionID/message") {
            applyAuth()
        }.body()
    }

    /**
     * Send async prompt (POST /session/{sessionID}/prompt_async).
     * Non-blocking: returns immediately; output streamed via /event SSE.
     */
    suspend fun sendPromptAsync(
        sessionID: String,
        request: ApiPromptAsyncRequest,
        directory: String? = null
    ) {
        httpClient.post("$baseUrl/session/$sessionID/prompt_async") {
            applyAuth()
            contentType(ContentType.Application.Json)
            if (directory != null) parameter("directory", directory)
            setBody(request)
        }
    }

    // --- Permissions ---

    /**
     * List pending permissions (GET /permission).
     */
    suspend fun listPermissions(directory: String? = null): List<ApiPermissionRequest> {
        return httpClient.get("$baseUrl/permission") {
            applyAuth()
            if (directory != null) parameter("directory", directory)
        }.body()
    }

    /**
     * Respond to a permission request (POST /permission/{requestID}/reply).
     */
    suspend fun replyPermission(
        requestID: String,
        request: ApiPermissionReplyRequest,
        directory: String? = null
    ): Boolean {
        return httpClient.post("$baseUrl/permission/$requestID/reply") {
            applyAuth()
            contentType(ContentType.Application.Json)
            if (directory != null) parameter("directory", directory)
            setBody(request)
        }.body()
    }

    // --- Questions ---

    /**
     * List pending questions (GET /question).
     */
    suspend fun listQuestions(directory: String? = null): List<ApiQuestionRequest> {
        return httpClient.get("$baseUrl/question") {
            applyAuth()
            if (directory != null) parameter("directory", directory)
        }.body()
    }

    /**
     * Reply to a question request (POST /question/{requestID}/reply).
     */
    suspend fun replyQuestion(
        requestID: String,
        request: ApiQuestionReplyRequest,
        directory: String? = null
    ): Boolean {
        return httpClient.post("$baseUrl/question/$requestID/reply") {
            applyAuth()
            contentType(ContentType.Application.Json)
            if (directory != null) parameter("directory", directory)
            setBody(request)
        }.body()
    }

    /**
     * Reject a question request (POST /question/{requestID}/reject).
     */
    suspend fun rejectQuestion(
        requestID: String,
        directory: String? = null
    ): Boolean {
        return httpClient.post("$baseUrl/question/$requestID/reject") {
            applyAuth()
            if (directory != null) parameter("directory", directory)
        }.body()
    }

    // --- Projects & Providers ---

    /**
     * List projects (GET /project).
     */
    suspend fun listProjects(): List<ApiProject> {
        return httpClient.get("$baseUrl/project") {
            applyAuth()
        }.body()
    }

    /**
     * List providers and models (GET /provider).
     */
    suspend fun listProviders(): List<ApiProvider> {
        return httpClient.get("$baseUrl/provider") {
            applyAuth()
        }.body()
    }
}
