package org.opencodemobile.shared.networking.client.generated.models

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.opencode.mobile.networking.client.generated.models.ApiActiveSessionsResponse
import org.opencode.mobile.networking.client.generated.models.ApiCursor
import org.opencode.mobile.networking.client.generated.models.ApiForm
import org.opencode.mobile.networking.client.generated.models.ApiFormListResponse
import org.opencode.mobile.networking.client.generated.models.ApiFormReplyRequest
import org.opencode.mobile.networking.client.generated.models.ApiLocation
import org.opencode.mobile.networking.client.generated.models.ApiMessagesResponse
import org.opencode.mobile.networking.client.generated.models.ApiPermissionListResponse
import org.opencode.mobile.networking.client.generated.models.ApiPermissionReplyRequest
import org.opencode.mobile.networking.client.generated.models.ApiServerInfo
import org.opencode.mobile.networking.client.generated.models.ApiServerPaths
import org.opencode.mobile.networking.client.generated.models.ApiSession
import org.opencode.mobile.networking.client.generated.models.ApiSessionActive
import org.opencode.mobile.networking.client.generated.models.ApiSessionResponse
import org.opencode.mobile.networking.client.generated.models.ApiSessionTime
import org.opencode.mobile.networking.client.generated.models.ApiSessionsResponse

/**
 * OPE-16: initial test that verifies the KMP test runner works.
 *
 * This lives in `commonTest`, so it executes on `androidUnitTest` (JVM) and on
 * the Apple targets from the same revision. A kotlinx.serialization round-trip
 * is deliberate: it proves both the kotlin.test harness and the serialization
 * plugin are wired identically on every target.
 *
 * The wire shapes are the opencode 2.0.6 `/api` HttpApi contracts.
 */
class GeneratedModelsSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun serverInfoRoundTrips() {
        val wire =
            """{"version":"2.0.6","pid":6,"urls":["http://172.20.0.2:4096"],"paths":{"tmp":"/tmp/opencode"}}"""

        val decoded = json.decodeFromString(ApiServerInfo.serializer(), wire)

        assertEquals(
            ApiServerInfo(
                version = "2.0.6",
                pid = 6,
                urls = listOf("http://172.20.0.2:4096"),
                paths = ApiServerPaths(tmp = "/tmp/opencode"),
            ),
            decoded,
        )
        assertEquals(wire, json.encodeToString(ApiServerInfo.serializer(), decoded))
    }

    @Test
    fun sessionDecodesOptionalFieldsAsNullAndNestedTimeAndLocation() {
        val decoded = json.decodeFromString(
            ApiSession.serializer(),
            """{"id":"s1","projectID":"p1","time":{"created":10,"updated":42},"location":{"directory":"/repo"}}""",
        )

        assertEquals("s1", decoded.id)
        assertEquals("p1", decoded.projectID)
        assertNull(decoded.title)
        assertNull(decoded.parentID)
        assertNull(decoded.agent)
        assertNull(decoded.model)
        assertEquals(ApiSessionTime(created = 10.0, updated = 42.0), decoded.time)
        assertEquals(ApiLocation(directory = "/repo"), decoded.location)
    }

    @Test
    fun sessionListIsADataEnvelopeWithACursor() {
        val decoded = json.decodeFromString(
            ApiSessionsResponse.serializer(),
            """{"data":[{"id":"s1","projectID":"p1","title":"T","parentID":"s0",""" +
                """"agent":"build","model":{"id":"m1","providerID":"pr"},""" +
                """"time":{"created":1,"updated":2},"location":{"directory":"/repo"}}],""" +
                """"cursor":{"next":"mock_1"}}""",
        )

        val session = decoded.data.single()
        assertEquals("s1", session.id)
        assertEquals("T", session.title)
        assertEquals("s0", session.parentID)
        assertEquals("build", session.agent)
        assertEquals("m1", session.model?.id)
        assertEquals("pr", session.model?.providerID)
        assertEquals(ApiCursor(previous = null, next = "mock_1"), decoded.cursor)
    }

    @Test
    fun singleSessionIsADataEnvelope() {
        val decoded = json.decodeFromString(
            ApiSessionResponse.serializer(),
            """{"data":{"id":"s1","projectID":"p1","time":{"created":1,"updated":2},""" +
                """"location":{"directory":"/repo"}}}""",
        )

        assertEquals("s1", decoded.data.id)
        assertEquals("/repo", decoded.data.location.directory)
    }

    @Test
    fun unknownFieldsAreIgnoredForForwardCompatibility() {
        val decoded = json.decodeFromString(
            ApiServerInfo.serializer(),
            """{"version":"2.0.6","pid":6,"urls":[],"paths":{"tmp":"/t","futurePath":"/x"},"futureField":123}""",
        )

        assertEquals(ApiServerInfo(version = "2.0.6", pid = 6, urls = emptyList(), paths = ApiServerPaths("/t")), decoded)
    }

    @Test
    fun userMessageCarriesItsText() {
        val message = decodeMessages(
            """{"data":[{"id":"msg_1","type":"user","time":{"created":1},"text":"hello"}],"cursor":{}}""",
        ).data.single()

        assertEquals("user", message.type)
        assertEquals("hello", message.text)
        assertTrue(message.content.isEmpty())
        assertNull(message.command)
        assertNull(message.output)
    }

    @Test
    fun assistantMessageCarriesContentPartsFinishAndCompletedTime() {
        val message = decodeMessages(
            """{"data":[{"id":"msg_2","type":"assistant","time":{"created":1,"completed":3},""" +
                """"agent":"build","model":{"id":"m1","providerID":"pr"},"finish":"stop",""" +
                """"content":[{"type":"text","text":"hi"},""" +
                """{"type":"tool","id":"call_1","name":"bash","state":{"status":"completed"}},""" +
                """{"type":"reasoning","text":"hmm"}]}],"cursor":{"previous":"mock_0"}}""",
        ).data.single()

        assertEquals("assistant", message.type)
        assertEquals(3.0, message.time.completed)
        assertEquals("stop", message.finish)
        assertEquals(listOf("text", "tool", "reasoning"), message.content.map { it.type })
        assertEquals("hi", message.content[0].text)
        assertEquals("call_1", message.content[1].id)
        assertEquals("bash", message.content[1].name)
        assertEquals(
            JsonPrimitive("completed"),
            message.content[1].state?.jsonObject?.get("status"),
        )
    }

    @Test
    fun shellMessageCarriesCommandAndOutput() {
        val message = decodeMessages(
            """{"data":[{"id":"msg_3","type":"shell","time":{"created":1},"shellID":"sh_1",""" +
                """"command":"ls -la","status":"completed","exit":0,""" +
                """"output":{"output":"a\nb","cursor":2,"size":3,"truncated":false}}],"cursor":{}}""",
        ).data.single()

        assertEquals("shell", message.type)
        assertEquals("ls -la", message.command)
        assertEquals("a\nb", message.output?.output)
    }

    @Test
    fun shellMessageWithoutOutputYetDecodesOutputAsNull() {
        val message = decodeMessages(
            """{"data":[{"id":"msg_4","type":"shell","time":{"created":1},"command":"sleep 5"}],"cursor":{}}""",
        ).data.single()

        assertEquals("sleep 5", message.command)
        assertNull(message.output)
    }

    @Test
    fun messageCursorKeepsBothDirections() {
        val decoded = decodeMessages("""{"data":[],"cursor":{"previous":"p","next":"n"}}""")

        assertEquals(ApiCursor(previous = "p", next = "n"), decoded.cursor)
    }

    @Test
    fun formListDecodesLocationAndRawFields() {
        val decoded = json.decodeFromString(
            ApiFormListResponse.serializer(),
            """{"location":{"directory":"/repo"},"data":[{"id":"frm_1","sessionID":"s1","title":"Pick",""" +
                """"metadata":{"k":"v"},"fields":[{"type":"multiselect","id":"branch"}]}]}""",
        )

        assertEquals(ApiLocation("/repo"), decoded.location)
        val form = decoded.data.single()
        assertEquals("frm_1", form.id)
        assertEquals("s1", form.sessionID)
        assertEquals("Pick", form.title)
        assertEquals(1, form.fields.size)
        assertEquals(JsonPrimitive("branch"), form.fields.single().jsonObject["id"])
    }

    @Test
    fun formWithoutFieldsIsRejected() {
        assertFails {
            json.decodeFromString(
                ApiForm.serializer(),
                """{"id":"frm_1","sessionID":"s1","title":"Pick","fields":[]}""",
            )
        }
    }

    @Test
    fun formReplyEncodesTheAnswerObject() {
        val answer = JsonObject(mapOf("branch" to JsonPrimitive("main")))

        val wire = json.encodeToString(ApiFormReplyRequest.serializer(), ApiFormReplyRequest(answer))

        assertEquals("""{"answer":{"branch":"main"}}""", wire)
    }

    @Test
    fun permissionListDecodesScopedRequests() {
        val decoded = json.decodeFromString(
            ApiPermissionListResponse.serializer(),
            """{"location":{"directory":"/repo"},"data":[{"id":"per_1","sessionID":"s1","action":"bash",""" +
                """"resources":["ls"],"save":["always"],"source":{"tool":"bash"},"message":"run ls"}]}""",
        )

        val request = decoded.data.single()
        assertEquals("per_1", request.id)
        assertEquals("s1", request.sessionID)
        assertEquals("bash", request.action)
        assertEquals(listOf("ls"), request.resources)
        assertEquals(listOf("always"), request.save)
        assertEquals("run ls", request.message)
        assertNull(request.metadata)
    }

    @Test
    fun permissionWithoutSaveDefaultsToEmpty() {
        val decoded = json.decodeFromString(
            ApiPermissionListResponse.serializer(),
            """{"data":[{"id":"per_1","sessionID":"s1","action":"edit","resources":["a.kt"]}]}""",
        )

        assertNull(decoded.location)
        assertTrue(decoded.data.single().save.isEmpty())
    }

    @Test
    fun permissionReplyEncodesTheDecisionOnly() {
        val wire = json.encodeToString(
            ApiPermissionReplyRequest.serializer(),
            ApiPermissionReplyRequest(decision = "once"),
        )

        assertEquals("""{"decision":"once"}""", wire)
    }

    @Test
    fun activeSessionsMapSessionIdToRunning() {
        val decoded = json.decodeFromString(
            ApiActiveSessionsResponse.serializer(),
            """{"data":{"s1":{"type":"running"},"s2":{"type":"running"}}}""",
        )

        assertEquals(setOf("s1", "s2"), decoded.data.keys)
        assertEquals(ApiSessionActive(type = "running"), decoded.data.getValue("s1"))
    }

    @Test
    fun activeSessionOtherThanRunningIsRejected() {
        assertFails { json.decodeFromString(ApiSessionActive.serializer(), """{"type":"idle"}""") }
        assertFails { ApiSessionActive(type = "retry") }
    }

    private fun decodeMessages(wire: String): ApiMessagesResponse =
        json.decodeFromString(ApiMessagesResponse.serializer(), wire)
}
