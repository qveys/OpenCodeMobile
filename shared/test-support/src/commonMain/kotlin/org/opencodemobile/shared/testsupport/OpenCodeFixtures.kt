package org.opencodemobile.shared.testsupport

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** A deterministic session fixture served by [MockOpenCodeServer] on `GET /session`. */
public data class MockSession(
    public val id: String,
    public val title: String,
    public val directory: String,
    public val created: Long,
    public val updated: Long,
)

/**
 * Pinned, deterministic payloads for [MockOpenCodeServer].
 *
 * Values are constants (no clock, no random, no IO) and the JSON shapes match the
 * pinned OpenCode Server v2 OpenAPI spec (`shared/networking/openapi/opencode-server-v2.json`,
 * v1.18.32) used to generate `OpenCodeApiClient`, so a mock response is byte-stable
 * across runs and platforms.
 */
public object OpenCodeFixtures {
    public const val SERVER_VERSION: String = "1.18.32"
    public const val UNSUPPORTED_SERVER_VERSION: String = "0.9.0"
    public const val PROJECT_ID: String = "prj_mock_0001"
    public const val DIRECTORY: String = "/home/dev/workspace/opencode-mobile"
    public const val BASE_TIME: Long = 1_700_000_000_000L

    public val sessions: List<MockSession> = listOf(
        MockSession(
            id = "ses_mock_0001",
            title = "Wire the connection screen",
            directory = DIRECTORY,
            created = BASE_TIME,
            updated = BASE_TIME + 100_000L,
        ),
        MockSession(
            id = "ses_mock_0002",
            title = "Reconcile the session snapshot",
            directory = DIRECTORY,
            created = BASE_TIME + 200_000L,
            updated = BASE_TIME + 300_000L,
        ),
    )

    /** `GET /global/health` body. */
    public fun healthJson(version: String = SERVER_VERSION): String =
        buildJsonObject {
            put("healthy", true)
            put("version", version)
        }.toString()

    /** One `ApiSession` JSON object. */
    public fun sessionObject(session: MockSession): JsonObject =
        buildJsonObject {
            put("id", session.id)
            put("slug", session.id.removePrefix("ses_"))
            put("projectID", PROJECT_ID)
            put("directory", session.directory)
            put("title", session.title)
            put("version", SERVER_VERSION)
            putJsonObject("time") {
                put("created", session.created)
                put("updated", session.updated)
            }
        }

    /** One `ApiSession` object, encoded as JSON text. */
    public fun sessionJson(session: MockSession): String = sessionObject(session).toString()

    /** `GET /session` body. */
    public fun sessionsJson(sessions: List<MockSession> = this.sessions): String =
        buildJsonArray {
            sessions.forEach { session -> add(sessionObject(session)) }
        }.toString()

    /**
     * `GET /event` script for the normal scenario: a session update followed by a streamed
     * assistant text part. Both carry well-formed JSON and stable ids.
     */
    public fun defaultSseEvents(
        sessionID: String = sessions.first().id,
    ): List<MockSseEvent> = listOf(
        MockSseEvent(
            id = "evt_mock_0001",
            type = "session.updated",
            data = buildJsonObject {
                put("type", "session.updated")
                putJsonObject("properties") {
                    putJsonObject("info") {
                        put("id", sessionID)
                        put("title", sessions.first().title)
                        put("directory", DIRECTORY)
                    }
                }
            }.toString(),
        ),
        MockSseEvent(
            id = "evt_mock_0002",
            type = "message.part.updated",
            data = buildJsonObject {
                put("type", "message.part.updated")
                putJsonObject("properties") {
                    put("sessionID", sessionID)
                    put("messageID", "msg_mock_0001")
                    putJsonObject("part") {
                        put("id", "prt_mock_0001")
                        put("type", "text")
                        put("text", "Streaming from the mock server.")
                    }
                }
            }.toString(),
        ),
    )

    /** `GET /event` script for [MockOpenCodeScenario.MalformedEvent]: a valid event then a truncated payload. */
    public fun malformedSseEvents(
        sessionID: String = sessions.first().id,
    ): List<MockSseEvent> = listOf(
        defaultSseEvents(sessionID).first(),
        MockSseEvent(
            id = "evt_mock_bad",
            type = "message.part.updated",
            data = """{"type":"message.part.updated","properties":{"part":""",
        ),
    )
}