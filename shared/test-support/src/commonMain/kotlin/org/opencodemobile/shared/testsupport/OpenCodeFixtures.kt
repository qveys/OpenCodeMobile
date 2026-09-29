package org.opencodemobile.shared.testsupport

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
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

    /** Server-issued id of the pending permission request exposed by [permissionObject]. */
    public const val PERMISSION_REQUEST_ID: String = "per_mock_0001"

    /** Server-issued id of the pending question exposed by [questionObject]. */
    public const val QUESTION_REQUEST_ID: String = "que_mock_0001"

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
        sessionUpdatedEvent(sessionID),
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

    /** The leading `session.updated` event shared by every scripted stream. */
    public fun sessionUpdatedEvent(sessionID: String = sessions.first().id): MockSseEvent =
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
        )

    /** One stable `message.part.updated` event, used to build the multi-event scripts. */
    public fun partUpdatedEvent(index: Int, sessionID: String = sessions.first().id): MockSseEvent {
        val suffix = index.toString().padStart(4, '0')
        return MockSseEvent(
            id = "evt_mock_part_$suffix",
            type = "message.part.updated",
            data = buildJsonObject {
                put("type", "message.part.updated")
                putJsonObject("properties") {
                    put("sessionID", sessionID)
                    put("messageID", "msg_mock_$suffix")
                    put("time", BASE_TIME + index)
                    putJsonObject("part") {
                        put("id", "prt_mock_$suffix")
                        put("type", "text")
                        put("text", "streamed chunk $index")
                    }
                }
            }.toString(),
        )
    }

    /** `GET /event` script for [MockOpenCodeScenario.Streaming]: a session update plus two parts. */
    public fun streamingSseEvents(): List<MockSseEvent> =
        listOf(sessionUpdatedEvent()) + (1..2).map { partUpdatedEvent(it) }

    /**
     * `GET /event` script for [MockOpenCodeScenario.Disconnect] and
     * [MockOpenCodeScenario.Reconnect]: a session update plus three parts.
     */
    public fun disconnectSseEvents(): List<MockSseEvent> =
        listOf(sessionUpdatedEvent()) + (1..3).map { partUpdatedEvent(it) }

    /** `GET /event` script for [MockOpenCodeScenario.SlowNetwork]: a session update plus two parts. */
    public fun slowNetworkSseEvents(): List<MockSseEvent> =
        listOf(sessionUpdatedEvent()) + (1..2).map { partUpdatedEvent(it) }

    /** `GET /event` script for [MockOpenCodeScenario.LongTranscript]: a session update plus many parts. */
    public fun longTranscriptSseEvents(partCount: Int = 600): List<MockSseEvent> =
        listOf(sessionUpdatedEvent()) + (1..partCount).map { partUpdatedEvent(it) }

    /**
     * `GET /event` script for [MockOpenCodeScenario.PermissionRequest]: a session update, a
     * `permission.asked` event and a `question.asked` event, so both the V1-06 permission flow
     * and the V1-07 pending-question flow have a fixture.
     */
    public fun permissionSseEvents(): List<MockSseEvent> = listOf(
        sessionUpdatedEvent(),
        permissionAskedEvent(),
        questionAskedEvent(),
    )

    /** One `ApiPermissionRequest` shape, as required by the pinned spec. */
    public fun permissionObject(): JsonObject = buildJsonObject {
        put("id", PERMISSION_REQUEST_ID)
        put("sessionID", sessions.first().id)
        put("permission", "bash")
        putJsonArray("patterns") { add("rm -rf build") }
        putJsonArray("always") { add("bash:rm") }
        putJsonObject("tool") {
            put("messageID", "msg_mock_0001")
            put("callID", "call_mock_0001")
        }
        putJsonObject("metadata") { put("command", "rm -rf build") }
    }

    /** `GET /permission` body. */
    public fun permissionsJson(): String = buildJsonArray { add(permissionObject()) }.toString()

    /** `permission.asked` SSE event wrapping [permissionObject]. */
    public fun permissionAskedEvent(): MockSseEvent = MockSseEvent(
        id = "evt_mock_permission_asked",
        type = "permission.asked",
        data = buildJsonObject {
            put("type", "permission.asked")
            put("properties", permissionObject())
        }.toString(),
    )

    /** `GET /question` body, in the pinned `ApiQuestionRequest` shape. */
    public fun questionsJson(): String = buildJsonArray { add(questionObject()) }.toString()

    /** One pending question, matching the pinned `ApiQuestionRequest` shape. */
    public fun questionObject(): JsonObject = buildJsonObject {
        put("id", QUESTION_REQUEST_ID)
        put("sessionID", sessions.first().id)
        putJsonArray("questions") {
            add(
                buildJsonObject {
                    put("question", "Which branch should I rebase onto?")
                    put("header", "Rebase target")
                    put("multiple", false)
                    put("custom", false)
                    putJsonArray("options") {
                        add(
                            buildJsonObject {
                                put("label", "main")
                                put("description", "Rebase onto main")
                            },
                        )
                        add(
                            buildJsonObject {
                                put("label", "release")
                                put("description", "Rebase onto release")
                            },
                        )
                    }
                },
            )
        }
        putJsonObject("tool") {
            put("messageID", "msg_mock_0001")
            put("callID", "call_mock_0002")
        }
    }

    /** `question.asked` SSE event wrapping [questionObject]. */
    public fun questionAskedEvent(): MockSseEvent = MockSseEvent(
        id = "evt_mock_question_asked",
        type = "question.asked",
        data = buildJsonObject {
            put("type", "question.asked")
            put("properties", questionObject())
        }.toString(),
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
