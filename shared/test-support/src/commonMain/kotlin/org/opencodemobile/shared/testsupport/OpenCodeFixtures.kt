package org.opencodemobile.shared.testsupport

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

public data class MockSession(
    public val id: String,
    public val title: String,
    public val directory: String,
    public val created: Long,
    public val updated: Long,
    public val parentId: String? = null,
)

/** Deterministic fixtures for the OpenCode 2.0.6 HTTP API and public event manifest. */
@Suppress("TooManyFunctions")
public object OpenCodeFixtures {
    public const val SERVER_VERSION: String = "2.0.6"
    public const val UNSUPPORTED_SERVER_VERSION: String = "1.18.32"
    public const val PROJECT_ID: String = "prj_mock_0001"
    public const val DIRECTORY: String = "/home/dev/workspace/opencode-mobile"
    public const val BASE_TIME: Long = 1_700_000_000_000L
    public const val PERMISSION_REQUEST_ID: String = "per_mock_0001"
    public const val FORM_REQUEST_ID: String = "frm_mock_0001"
    public const val SESSION_STATUS_RETRY_ATTEMPT: Int = 2
    public const val SESSION_STATUS_RETRY_MESSAGE: String = "Provider rate limited; retrying"
    public const val SESSION_STATUS_RETRY_NEXT: Long = BASE_TIME + 5_000L
    public const val LIVE_TAIL_EVENT_INDEX: Int = 100
    public const val PROVIDER_ID: String = "anthropic"
    public const val MODEL_ID: String = "claude-sonnet-4"
    public const val AGENT_NAME: String = "build"

    public val sessions: List<MockSession> = listOf(
        MockSession("ses_mock_0001", "Wire the connection screen", DIRECTORY, BASE_TIME, BASE_TIME + 100_000L),
        MockSession("ses_mock_0002", "Reconcile the session snapshot", DIRECTORY, BASE_TIME + 200_000L, BASE_TIME + 300_000L),
    )

    public fun locationObject(directory: String = DIRECTORY): JsonObject = buildJsonObject { put("directory", directory) }

    public fun envelope(data: JsonElement, location: Boolean = false, cursor: Boolean = false): JsonObject =
        buildJsonObject {
            put("data", data)
            if (location) put("location", locationObject())
            if (cursor) putJsonObject("cursor") { put("previous", JsonNull); put("next", JsonNull) }
        }

    public fun infoJson(version: String = SERVER_VERSION): String = buildJsonObject {
        put("version", version)
        put("pid", 6)
        putJsonArray("urls") { add("http://mock.opencode.test") }
        putJsonObject("paths") { put("tmp", "/tmp/opencode") }
    }.toString()

    public fun tokensObject(): JsonObject = buildJsonObject {
        put("input", 0); put("output", 0); put("reasoning", 0)
        putJsonObject("cache") { put("read", 0); put("write", 0) }
    }

    public fun sessionObject(session: MockSession): JsonObject = buildJsonObject {
        put("id", session.id)
        put("projectID", PROJECT_ID)
        put("title", session.title)
        put("location", locationObject(session.directory))
        put("cost", 0)
        put("tokens", tokensObject())
        session.parentId?.let { put("parentID", it) }
        putJsonObject("time") { put("created", session.created); put("updated", session.updated) }
    }

    public fun sessionJson(session: MockSession): String = envelope(sessionObject(session)).toString()

    public fun sessionsJson(sessions: List<MockSession> = this.sessions): String =
        envelope(buildJsonArray { sessions.forEach { add(sessionObject(it)) } }, cursor = true).toString()

    /** Active-session snapshots list running sessions only (first one); retry details arrive through session.status. */
    public fun sessionStatusJson(sessions: List<MockSession> = this.sessions): String = envelope(buildJsonObject {
        sessions.take(1).forEach { session ->
            putJsonObject(session.id) { put("type", "running") }
        }
    }).toString()

    public fun sessionStatusObject(retry: Boolean = false): JsonObject = buildJsonObject {
        put("type", if (retry) "retry" else "idle")
        if (retry) {
            put("attempt", SESSION_STATUS_RETRY_ATTEMPT)
            put("message", SESSION_STATUS_RETRY_MESSAGE)
            put("next", SESSION_STATUS_RETRY_NEXT)
        }
    }

    public fun event(id: String, type: String, data: JsonObject, created: Long? = BASE_TIME): MockSseEvent =
        MockSseEvent(id, type, buildJsonObject {
            put("id", id); put("type", type)
            created?.let { put("created", it) }
            put("data", data)
        }.toString())

    public fun connectedEvent(connection: Int = 1): MockSseEvent =
        event("evt_connected_$connection", "server.connected", buildJsonObject {}, created = null)

    public fun sessionUpdatedEvent(sessionID: String = sessions.first().id): MockSseEvent =
        event("evt_mock_0001", "session.status", buildJsonObject {
            put("sessionID", sessionID); put("status", sessionStatusObject(retry = true))
        })

    /** A complete text block; ordinal identifies the block inside the assistant message. */
    @Suppress("LongParameterList")
    public fun partUpdatedSseEvent(
        eventId: String,
        messageId: String,
        text: String,
        sessionID: String = sessions.first().id,
        time: Long = BASE_TIME,
    ): MockSseEvent = event(eventId, "session.text.ended", buildJsonObject {
        put("sessionID", sessionID); put("assistantMessageID", messageId)
        put("ordinal", 0); put("text", text)
    }, time)

    public fun partUpdatedEvent(index: Int, sessionID: String = sessions.first().id): MockSseEvent {
        val suffix = index.toString().padStart(4, '0')
        return partUpdatedSseEvent("evt_mock_part_$suffix", "msg_mock_$suffix", "streamed chunk $index", sessionID, BASE_TIME + index)
    }

    public fun defaultSseEvents(sessionID: String = sessions.first().id): List<MockSseEvent> = listOf(
        sessionUpdatedEvent(sessionID),
        partUpdatedSseEvent("evt_mock_0002", "msg_mock_0001", "Streaming from the mock server.", sessionID),
    )
    public fun streamingSseEvents(): List<MockSseEvent> = listOf(sessionUpdatedEvent()) + (1..2).map { partUpdatedEvent(it) }
    public fun disconnectSseEvents(): List<MockSseEvent> = listOf(sessionUpdatedEvent()) + (1..3).map { partUpdatedEvent(it) }
    public fun slowNetworkSseEvents(): List<MockSseEvent> = streamingSseEvents()
    public fun abortSseEvents(): List<MockSseEvent> = disconnectSseEvents()
    public fun longTranscriptSseEvents(partCount: Int = 600): List<MockSseEvent> = listOf(sessionUpdatedEvent()) + (1..partCount).map { partUpdatedEvent(it) }
    public fun liveTailSseEvents(index: Int = LIVE_TAIL_EVENT_INDEX, sessionID: String = sessions.first().id): List<MockSseEvent> = listOf(partUpdatedEvent(index, sessionID))
    public fun malformedSseEvents(sessionID: String = sessions.first().id): List<MockSseEvent> = listOf(
        sessionUpdatedEvent(sessionID), MockSseEvent("evt_mock_bad", "session.text.ended", """{"id":"evt_mock_bad","type":"session.text.ended","data":{ """),
    )

    public fun textPartObject(partId: String, messageId: String, text: String, sessionID: String = sessions.first().id, start: Long = BASE_TIME): JsonObject =
        buildJsonObject { put("type", "text"); put("text", text) }

    public fun messageEnvelopeObject(messageId: String, role: String, parts: List<JsonObject>, sessionID: String = sessions.first().id, time: Long = BASE_TIME): JsonObject = buildJsonObject {
        put("id", messageId); put("type", role)
        putJsonObject("time") { put("created", time) }
        if (role == "user") {
            put("text", parts.joinToString("") { (it["text"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty() })
        } else {
            put("agent", AGENT_NAME)
            putJsonObject("model") { put("providerID", PROVIDER_ID); put("id", MODEL_ID) }
            putJsonArray("content") { parts.forEach { add(it) } }
        }
    }

    public fun userMessageObject(index: Int, text: String, sessionID: String = sessions.first().id): JsonObject =
        messageEnvelopeObject("msg_user_${index.toString().padStart(4, '0')}", "user", listOf(buildJsonObject { put("type", "text"); put("text", text) }), sessionID)

    public fun assistantMessageObject(index: Int, sessionID: String = sessions.first().id): JsonObject =
        messageEnvelopeObject("msg_mock_${index.toString().padStart(4, '0')}", "assistant", listOf(buildJsonObject { put("type", "text"); put("text", "streamed chunk $index") }), sessionID)

    public fun transcriptJson(assistantIndices: List<Int>, userPrompts: List<String> = emptyList(), sessionID: String = sessions.first().id): String = envelope(buildJsonArray {
        userPrompts.forEachIndexed { index, text -> add(userMessageObject(index + 1, text, sessionID)) }
        assistantIndices.forEach { add(assistantMessageObject(it, sessionID)) }
    }, cursor = true).toString()

    public fun permissionObject(): JsonObject = buildJsonObject {
        put("id", PERMISSION_REQUEST_ID); put("sessionID", sessions.first().id); put("action", "bash")
        putJsonArray("resources") { add("rm -rf build") }
        putJsonArray("save") { add("bash:rm") }
        putJsonObject("source") { put("type", "tool"); put("messageID", "msg_mock_0001"); put("id", "call_mock_0001") }
        putJsonObject("metadata") { put("command", "rm -rf build") }
    }
    public fun permissionsJson(): String = envelope(buildJsonArray { add(permissionObject()) }, location = true).toString()
    public fun permissionAskedEvent(): MockSseEvent = event("evt_mock_permission_asked", "permission.asked", permissionObject())

    public fun formObject(): JsonObject = buildJsonObject {
        put("id", FORM_REQUEST_ID); put("sessionID", sessions.first().id); put("title", "Rebase target")
        putJsonArray("fields") {
            add(buildJsonObject {
                put("key", "branch"); put("type", "multiselect"); put("title", "Which branch should I rebase onto?")
                put("required", true); put("custom", false)
                putJsonArray("options") {
                    add(buildJsonObject { put("value", "main"); put("label", "main"); put("description", "Rebase onto main") })
                    add(buildJsonObject { put("value", "release"); put("label", "release"); put("description", "Rebase onto release") })
                }
            })
        }
    }
    public fun formsJson(): String = envelope(buildJsonArray { add(formObject()) }, location = true).toString()
    public fun formCreatedEvent(): MockSseEvent = event("evt_mock_form_created", "form.created", buildJsonObject { put("form", formObject()) })
    public fun permissionSseEvents(): List<MockSseEvent> = listOf(sessionUpdatedEvent(), permissionAskedEvent(), formCreatedEvent())

    public fun providersJson(): String = envelope(buildJsonArray {
        add(providerObject(PROVIDER_ID, "Anthropic")); add(providerObject("openai", "OpenAI"))
    }, location = true).toString()
    public fun emptyProvidersJson(): String = envelope(buildJsonArray {}, location = true).toString()
    private fun providerObject(id: String, name: String): JsonObject = buildJsonObject {
        put("id", id); put("name", name); put("activation", "enabled"); put("package", "@ai-sdk/$id")
    }
    public fun modelsJson(): String = envelope(buildJsonArray {
        add(buildJsonObject {
            put("id", MODEL_ID); put("modelID", MODEL_ID); put("providerID", PROVIDER_ID); put("name", "Claude Sonnet 4")
            putJsonObject("capabilities") {
                put("tools", true); putJsonArray("input") { add("text") }; putJsonArray("output") { add("text") }
            }
            putJsonArray("variants") {}; putJsonArray("cost") {}
            putJsonObject("time") { put("released", BASE_TIME) }
            put("status", "active"); put("enabled", true)
            putJsonObject("limit") { put("context", 200000); put("output", 16000) }
        })
    }, location = true).toString()
    public fun agentsJson(): String = envelope(buildJsonArray {
        add(agentObject(AGENT_NAME, "Default primary agent")); add(agentObject("plan", "Read-only planning agent"))
    }, location = true).toString()
    private fun agentObject(name: String, description: String): JsonObject = buildJsonObject {
        put("id", name); put("name", name); put("description", description); put("mode", "primary"); put("hidden", false)
        putJsonArray("permissions") {}
        putJsonObject("request") { putJsonObject("settings") {}; putJsonObject("headers") {}; putJsonObject("body") {} }
    }
}
