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
    /** Server-issued parent session id when this session was forked, else null. */
    public val parentId: String? = null,
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

    /** Retry `attempt` reported by [sessionStatusJson] for the first fixture session. */
    public const val SESSION_STATUS_RETRY_ATTEMPT: Int = 2

    /** Retry `message` reported by [sessionStatusJson] for the first fixture session. */
    public const val SESSION_STATUS_RETRY_MESSAGE: String = "Provider rate limited; retrying"

    /** Retry `next` timestamp reported by [sessionStatusJson] for the first fixture session. */
    public const val SESSION_STATUS_RETRY_NEXT: Long = BASE_TIME + 5_000L

    /** Id of the late event served by [liveTailSseEvents] as a post-reconnect live tail. */
    public const val LIVE_TAIL_EVENT_INDEX: Int = 100

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
            if (session.parentId != null) {
                put("parentID", session.parentId)
            }
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
     * `GET /doc` body: the server's own published surface, used to detect
     * optional capabilities at runtime. When [forkAvailable] is false the fork
     * route is absent, exactly like a server build that never exposed it.
     */
    public fun serverDocumentJson(forkAvailable: Boolean = true): String =
        buildJsonObject {
            put("openapi", "3.1.0")
            putJsonObject("paths") {
                putJsonObject("/session") {
                    putJsonObject("get") { }
                    putJsonObject("post") { }
                }
                putJsonObject("/session/{sessionID}") {
                    putJsonObject("get") { }
                    putJsonObject("patch") { }
                    putJsonObject("delete") { }
                }
                if (forkAvailable) {
                    putJsonObject("/session/{sessionID}/fork") {
                        putJsonObject("post") { }
                    }
                }
            }
        }.toString()

    /**
     * `GET /session/status` body, shaped like the pinned `Map<String, ApiSessionStatus>` the
     * generated client expects (the route the fallback polling in `docs/ARCHITECTURE.md` §3.2
     * reads). The first session reports a `retry` (the state reconciliation must not lose), the
     * remaining sessions report `idle`.
     */
    public fun sessionStatusJson(sessions: List<MockSession> = this.sessions): String =
        buildJsonObject {
            sessions.forEachIndexed { index, session ->
                put(session.id, sessionStatusObject(retry = index == 0))
            }
        }.toString()

    /** One `ApiSessionStatus` object. `retry` fills `attempt`/`message`/`next`, otherwise `idle`. */
    public fun sessionStatusObject(retry: Boolean = false): JsonObject = buildJsonObject {
        if (retry) {
            put("type", "retry")
            put("attempt", SESSION_STATUS_RETRY_ATTEMPT)
            put("message", SESSION_STATUS_RETRY_MESSAGE)
            put("next", SESSION_STATUS_RETRY_NEXT)
        } else {
            put("type", "idle")
        }
    }

    /**
     * `GET /event` script for the normal scenario: a session update followed by a streamed
     * assistant text part. Both carry well-formed JSON and stable ids.
     */
    public fun defaultSseEvents(
        sessionID: String = sessions.first().id,
    ): List<MockSseEvent> = listOf(
        sessionUpdatedEvent(sessionID),
        partUpdatedSseEvent(
            eventId = "evt_mock_0002",
            partId = "prt_mock_0001",
            messageId = "msg_mock_0001",
            text = "Streaming from the mock server.",
            sessionID = sessionID,
        ),
    )

    /**
     * One spec-conformant `TextPart` object.
     *
     * The pinned spec's `TextPart` requires `id, sessionID, messageID, type, text`:
     * the `messageID` is carried by the **part**, not by the enclosing event's
     * `properties` (which is `{sessionID, part, time}`, `additionalProperties:false`).
     */
    public fun textPartObject(
        partId: String,
        messageId: String,
        text: String,
        sessionID: String = sessions.first().id,
        start: Long = BASE_TIME,
    ): JsonObject = buildJsonObject {
        put("id", partId)
        put("sessionID", sessionID)
        put("messageID", messageId)
        put("type", "text")
        put("text", text)
        putJsonObject("time") { put("start", start) }
    }

    /**
     * One spec-conformant `message.part.updated` event
     * (`EventMessagePartUpdated`: `properties = {sessionID, part, time}`).
     */
    public fun partUpdatedSseEvent(
        eventId: String,
        partId: String,
        messageId: String,
        text: String,
        sessionID: String = sessions.first().id,
        time: Long = BASE_TIME,
    ): MockSseEvent = MockSseEvent(
        id = eventId,
        type = "message.part.updated",
        data = buildJsonObject {
            put("type", "message.part.updated")
            putJsonObject("properties") {
                put("sessionID", sessionID)
                put("part", textPartObject(partId, messageId, text, sessionID, time))
                put("time", time)
            }
        }.toString(),
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
        return partUpdatedSseEvent(
            eventId = "evt_mock_part_$suffix",
            partId = "prt_mock_$suffix",
            messageId = "msg_mock_$suffix",
            text = "streamed chunk $index",
            sessionID = sessionID,
            time = BASE_TIME + index,
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

    /**
     * `GET /event` activity that arrives **after** the main script on a connection that stays
     * open ([MockOpenCodeStreamConfig.tailEvents]): a single late `message.part.updated` whose
     * id sorts after the reconnect script so it cannot be mistaken for a replayed event.
     */
    public fun liveTailSseEvents(
        index: Int = LIVE_TAIL_EVENT_INDEX,
        sessionID: String = sessions.first().id,
    ): List<MockSseEvent> = listOf(partUpdatedEvent(index, sessionID))

    /**
     * `GET /event` script for [MockOpenCodeScenario.Abort]: a session update plus
     * three parts. The third part is never delivered once the turn is aborted
     * (`MockOpenCodeStreamConfig.abortHoldAfterEvents` holds after the first part).
     */
    public fun abortSseEvents(): List<MockSseEvent> =
        listOf(sessionUpdatedEvent()) + (1..3).map { partUpdatedEvent(it) }

    /** `GET /event` script for [MockOpenCodeScenario.SlowNetwork]: a session update plus two parts. */
    public fun slowNetworkSseEvents(): List<MockSseEvent> =
        listOf(sessionUpdatedEvent()) + (1..2).map { partUpdatedEvent(it) }

    /** `GET /event` script for [MockOpenCodeScenario.LongTranscript]: a session update plus many parts. */
    public fun longTranscriptSseEvents(partCount: Int = 600): List<MockSseEvent> =
        listOf(sessionUpdatedEvent()) + (1..partCount).map { partUpdatedEvent(it) }

    // --- V1-05: transcript messages (`GET /session/{id}/message`) ---

    /**
     * One `GET /session/{id}/message` element, in the pinned spec shape:
     * `{ info: Message, parts: Part[] }` (the message identity/role live in
     * `info`; the parts are a sibling array).
     */
    public fun messageEnvelopeObject(
        messageId: String,
        role: String,
        parts: List<JsonObject>,
        sessionID: String = sessions.first().id,
        time: Long = BASE_TIME,
    ): JsonObject = buildJsonObject {
        putJsonObject("info") {
            put("id", messageId)
            put("sessionID", sessionID)
            put("role", role)
            putJsonObject("time") {
                put("created", time)
                put("updated", time)
            }
        }
        putJsonArray("parts") { parts.forEach { add(it) } }
    }

    /**
     * One `{info, parts}` envelope for a user prompt recorded on
     * `POST /session/{id}/prompt_async`.
     */
    public fun userMessageObject(
        index: Int,
        text: String,
        sessionID: String = sessions.first().id,
    ): JsonObject {
        val suffix = index.toString().padStart(4, '0')
        return messageEnvelopeObject(
            messageId = "msg_user_$suffix",
            role = "user",
            parts = listOf(
                textPartObject(
                    partId = "prt_user_$suffix",
                    messageId = "msg_user_$suffix",
                    text = text,
                    sessionID = sessionID,
                ),
            ),
            sessionID = sessionID,
        )
    }

    /**
     * One assistant `{info, parts}` envelope derived from a scripted
     * `message.part.updated` part, so the SSE stream and
     * `GET /session/{id}/message` stay consistent.
     */
    public fun assistantMessageObject(
        index: Int,
        sessionID: String = sessions.first().id,
    ): JsonObject {
        val suffix = index.toString().padStart(4, '0')
        return messageEnvelopeObject(
            messageId = "msg_mock_$suffix",
            role = "assistant",
            parts = listOf(
                textPartObject(
                    partId = "prt_mock_$suffix",
                    messageId = "msg_mock_$suffix",
                    text = "streamed chunk $index",
                    sessionID = sessionID,
                ),
            ),
            sessionID = sessionID,
        )
    }

    /** `GET /session/{id}/message` body: user prompts first, then assistant messages. */
    public fun transcriptJson(
        assistantIndices: List<Int>,
        userPrompts: List<String> = emptyList(),
        sessionID: String = sessions.first().id,
    ): String = buildJsonArray {
        userPrompts.forEachIndexed { index, text ->
            add(userMessageObject(index + 1, text, sessionID))
        }
        assistantIndices.forEach { add(assistantMessageObject(it, sessionID)) }
    }.toString()

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

    // --- V1-09: server-exposed models and agents ---

    /** Provider id of the first fixture model provider (`GET /provider`). */
    public const val PROVIDER_ID: String = "anthropic"

    /** Model id of the first fixture model. */
    public const val MODEL_ID: String = "claude-sonnet-4"

    /** Agent name of the first fixture agent (`GET /agent`). */
    public const val AGENT_NAME: String = "build"

    /**
     * `GET /provider` body in the pinned spec shape: an object with `all`
     * (providers, each holding a model map keyed by model id), `default`
     * (provider id -> default model id) and `connected`.
     */
    public fun providersJson(): String = buildJsonObject {
        putJsonArray("all") {
            add(providerObject(PROVIDER_ID, "Anthropic", MODEL_ID, "Claude Sonnet 4"))
            add(providerObject("openai", "OpenAI", "gpt-5", "GPT-5"))
        }
        putJsonObject("default") { put(PROVIDER_ID, MODEL_ID) }
        putJsonArray("connected") { add(PROVIDER_ID) }
    }.toString()

    /** `GET /provider` body for a server that exposes no provider at all. */
    public fun emptyProvidersJson(): String = buildJsonObject {
        putJsonArray("all") { }
        putJsonObject("default") { }
        putJsonArray("connected") { }
    }.toString()

    private fun providerObject(
        providerId: String,
        providerName: String,
        modelId: String,
        modelName: String,
    ): JsonObject = buildJsonObject {
        put("id", providerId)
        put("name", providerName)
        putJsonObject("models") {
            putJsonObject(modelId) {
                put("id", modelId)
                put("name", modelName)
                put("providerID", providerId)
            }
        }
    }

    /** `GET /agent` body: two primary agents in the pinned `Agent` shape. */
    public fun agentsJson(): String = buildJsonArray {
        add(agentObject(AGENT_NAME, "Default primary agent"))
        add(agentObject("plan", "Read-only planning agent"))
    }.toString()

    private fun agentObject(name: String, description: String): JsonObject = buildJsonObject {
        put("name", name)
        put("description", description)
        put("mode", "primary")
        put("native", true)
        put("hidden", false)
        put("permission", buildJsonObject { })
        put("options", buildJsonObject { })
    }
}

