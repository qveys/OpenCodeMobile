package org.opencodemobile.shared.testsupport

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.prepareGet
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class MockOpenCodeServerTest {
    private val servers = mutableListOf<MockOpenCodeServer>()
    private fun server(scenario: MockOpenCodeScenario = MockOpenCodeScenario.Default, config: MockOpenCodeStreamConfig = MockOpenCodeStreamConfig()): MockOpenCodeServer =
        MockOpenCodeServer(scenario, streamConfig = config).start().also { servers += it }
    private fun obj(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject
    private val sessionId = OpenCodeFixtures.sessions.first().id

    @AfterTest fun cleanup() { servers.forEach { it.stop() } }

    @Test fun lifecycleIsIdempotent() {
        val server = server()
        assertTrue(server.isRunning)
        val client = server.client
        server.start()
        assertTrue(client === server.client)
        server.stop()
        server.stop()
        assertFalse(server.isRunning)
    }

    @Test fun infoUsesVersionTwoAndLegacyRoutesAreAbsent() = runTest {
        val server = server()
        val info = obj(server.client.get("${server.baseUrl}/api/info").bodyAsText())
        assertEquals("2.0.6", info["version"]?.jsonPrimitive?.content)
        assertNotNull(info["pid"])
        assertNull(info["healthy"])
        for (path in listOf("/global/health", "/doc", "/session", "/event", "/question", "/api/health")) {
            assertEquals(HttpStatusCode.NotFound, server.client.get("${server.baseUrl}$path").status)
        }
    }

    @Test fun sessionCrudAndForkUseV2EnvelopesAndStatuses() = runTest {
        val server = server()
        val base = "${server.baseUrl}/api/session"
        val list = obj(server.client.get(base).bodyAsText())
        assertEquals(2, list["data"]?.jsonArray?.size)
        assertNotNull(list["cursor"])
        val first = list["data"]!!.jsonArray.first().jsonObject
        assertEquals(OpenCodeFixtures.DIRECTORY, first["location"]?.jsonObject?.get("directory")?.jsonPrimitive?.content)
        assertNull(first["directory"])
        val created = server.client.post(base) { setBody("""{"title":"Created"}""") }
        assertEquals(HttpStatusCode.OK, created.status)
        val id = obj(created.bodyAsText())["data"]!!.jsonObject["id"]!!.jsonPrimitive.content
        assertEquals(HttpStatusCode.NoContent, server.client.patch("$base/$id") { setBody("""{"title":"Renamed"}""") }.status)
        assertEquals("Renamed", obj(server.client.get("$base/$id").bodyAsText())["data"]!!.jsonObject["title"]!!.jsonPrimitive.content)
        val fork = obj(server.client.post("$base/$id/fork") { setBody("{}") }.bodyAsText())["data"]!!.jsonObject
        assertEquals(id, fork["parentID"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.NoContent, server.client.delete("$base/$id").status)
        assertEquals(HttpStatusCode.NotFound, server.client.get("$base/$id").status)
        assertEquals(HttpStatusCode.NotFound, server.client.delete("$base/$id/nonexistent").status)
    }

    @Test fun sessionPagesAndCreationLocationMatchV2() = runTest {
        val server = server()
        val base = "${server.baseUrl}/api/session"
        val first = obj(server.client.get("$base?limit=1").bodyAsText())
        assertEquals(1, first["data"]!!.jsonArray.size)
        val next = first["cursor"]!!.jsonObject["next"]!!.jsonPrimitive.content
        val second = obj(server.client.get("$base?limit=1&cursor=$next").bodyAsText())
        assertFalse(first["data"]!!.jsonArray.single() == second["data"]!!.jsonArray.single())
        val created = obj(server.client.post(base) { setBody("""{"title":"Other project","location":{"directory":"/tmp/other"}}""") }.bodyAsText())["data"]!!.jsonObject
        assertEquals("/tmp/other", created["location"]!!.jsonObject["directory"]!!.jsonPrimitive.content)
        assertEquals(1, obj(server.client.get("$base?directory=/tmp/other").bodyAsText())["data"]!!.jsonArray.size)
    }

    @Test fun promptAndTranscriptUseTextAndContent() = runTest {
        val server = server()
        val base = "${server.baseUrl}/api/session/$sessionId"
        val response = server.client.post("$base/prompt") { setBody("""{"text":"hello"}""") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertNotNull(obj(response.bodyAsText())["data"])
        assertEquals("hello", server.prompts.single().text)
        val transcript = obj(server.client.get("$base/message").bodyAsText())
        assertNotNull(transcript["cursor"])
        val messages = transcript["data"]!!.jsonArray
        assertEquals("user", messages.first().jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("hello", messages.first().jsonObject["text"]!!.jsonPrimitive.content)
        assertNotNull(messages.last().jsonObject["content"])
        assertNull(messages.last().jsonObject["info"])
    }

    @Test fun activeSessionsExposeOnlyType() = runTest {
        val server = server()
        val data = obj(server.client.get("${server.baseUrl}/api/session/active").bodyAsText())["data"]!!.jsonObject
        assertEquals(setOf("type"), data[sessionId]!!.jsonObject.keys)
        assertEquals("running", data[sessionId]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test fun permissionsAndFormsAreSessionScoped() = runTest {
        val server = server(MockOpenCodeScenario.PermissionRequest)
        val base = server.baseUrl
        val pending = obj(server.client.get("$base/api/permission/request").bodyAsText())
        assertNotNull(pending["location"])
        val request = pending["data"]!!.jsonArray.single().jsonObject
        assertEquals("bash", request["action"]!!.jsonPrimitive.content)
        assertNotNull(request["resources"])
        val permissionPath = "$base/api/session/$sessionId/permission/${OpenCodeFixtures.PERMISSION_REQUEST_ID}/reply"
        assertEquals(HttpStatusCode.NoContent, server.client.post(permissionPath) { setBody("""{"decision":"once"}""") }.status)
        assertEquals(sessionId, server.permissionReplies.single().sessionId)
        assertEquals("once", server.permissionReplies.single().reply)
        assertEquals(HttpStatusCode.BadRequest, server.client.post(permissionPath) { setBody("""{"reply":"once"}""") }.status)
        val forms = obj(server.client.get("$base/api/form").bodyAsText())["data"]!!.jsonArray
        assertEquals("frm_mock_0001", forms.single().jsonObject["id"]!!.jsonPrimitive.content)
        assertNotNull(forms.single().jsonObject["fields"])
        val formPath = "$base/api/session/$sessionId/form/${OpenCodeFixtures.FORM_REQUEST_ID}"
        assertEquals(HttpStatusCode.NoContent, server.client.post("$formPath/reply") { setBody("""{"answer":{"branch":["main"]}}""") }.status)
        assertEquals("main", server.formReplies.single().answer!!["branch"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals(HttpStatusCode.NoContent, server.client.delete(formPath).status)
        assertEquals("cancel", server.formReplies.last().decision)
        assertEquals(HttpStatusCode.NotFound, server.client.post("$base/api/session/ses_wrong/form/${OpenCodeFixtures.FORM_REQUEST_ID}/reply") { setBody("""{"answer":{}}""") }.status)
    }

    @Test fun streamHasJsonOnlyFramesConnectedWithoutCreatedAndHeartbeat() = runTest {
        val server = server(MockOpenCodeScenario.PermissionRequest)
        val response = server.client.get("${server.baseUrl}/api/event")
        assertEquals("no-cache, no-transform", response.headers["Cache-Control"])
        val raw = response.bodyAsText()
        assertFalse(raw.lineSequence().any { it.startsWith("event:") || it.startsWith("id:") })
        assertTrue(raw.contains(": heartbeat\n\n"))
        val events = MockSseCodec.decode(raw)
        assertEquals("server.connected", events.first().type)
        val connected = obj(events.first().data)
        assertNull(connected["created"])
        assertEquals(JsonObject(emptyMap()), connected["data"])
        assertEquals(listOf("server.connected", "session.status", "permission.asked", "form.created"), events.map { it.type })
        assertNotNull(obj(events[2].data)["data"]!!.jsonObject["action"])
    }

    @Test fun reconnectIgnoresLastEventIdAndNeverReplays() = runTest {
        val server = server(MockOpenCodeScenario.Reconnect)
        val url = "${server.baseUrl}/api/event"
        val first = MockSseCodec.decode(server.client.get(url).bodyAsText())
        val second = MockSseCodec.decode(server.client.get(url) { header("Last-Event-ID", first[1].id) }.bodyAsText())
        assertEquals("server.connected", second.first().type)
        val oldIds = first.drop(1).map { it.id }.toSet()
        assertTrue(second.drop(1).none { it.id in oldIds })
        assertEquals(2, server.eventStreamConnections)
    }

    @Test fun streamingDeliversAnEventBeforeTheDelayedRemainder() = runTest {
        val server = server(MockOpenCodeScenario.Streaming, MockOpenCodeStreamConfig(streamingDelayMillis = 50))
        val channel = server.client.get("${server.baseUrl}/api/event").bodyAsChannel()
        assertEquals("server.connected", readEvent(channel)?.type)
        assertEquals("session.status", readEvent(channel)?.type)
        assertEquals("session.text.ended", readEvent(channel)?.type)
        assertEquals("session.text.ended", readEvent(channel)?.type)
        assertNull(readEvent(channel))
    }

    @Test fun abortStopsFutureContentAndTranscriptKeepsCommittedContent() = runTest {
        val server = server(MockOpenCodeScenario.Abort)
        server.client.prepareGet("${server.baseUrl}/api/event").execute { stream ->
        val channel = stream.bodyAsChannel()
        assertEquals("server.connected", readEvent(channel)?.type)
        assertEquals("session.status", readEvent(channel)?.type)
        assertEquals("session.text.ended", readEvent(channel)?.type)
        val response = server.client.post("${server.baseUrl}/api/session/$sessionId/interrupt?directory=/tmp/project")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("true", obj(response.bodyAsText())["interrupted"]!!.jsonPrimitive.content)
        assertEquals("/tmp/project", server.aborts.single().directory)
        assertEquals("session.text.ended", readEvent(channel)?.type)
        assertNull(readEvent(channel))
        }
        val transcript = obj(server.client.get("${server.baseUrl}/api/session/$sessionId/message").bodyAsText())["data"]!!.jsonArray
        assertEquals(1, transcript.size)
        assertEquals("msg_mock_0001", transcript.single().jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test fun malformedEventIsPreservedAndLongTranscriptIsBounded() = runTest {
        val bad = server(MockOpenCodeScenario.MalformedEvent)
        val events = MockSseCodec.decode(bad.client.get("${bad.baseUrl}/api/event").bodyAsText())
        assertTrue(runCatching { Json.parseToJsonElement(events.last().data) }.isFailure)
        val long = server(MockOpenCodeScenario.LongTranscript, MockOpenCodeStreamConfig(longTranscriptPartCount = 600))
        val all = MockSseCodec.decode(long.client.get("${long.baseUrl}/api/event").bodyAsText())
        assertEquals(600, all.count { it.type == "session.text.ended" })
    }

    @Test fun unsupportedAuthenticationAndServerFailuresRemainInjectable() = runTest {
        val old = server(MockOpenCodeScenario.UnsupportedVersion)
        assertEquals("1.18.32", obj(old.client.get("${old.baseUrl}/api/info").bodyAsText())["version"]!!.jsonPrimitive.content)
        val auth = server(MockOpenCodeScenario.AuthenticationFailure)
        assertEquals(HttpStatusCode.Unauthorized, auth.client.get("${auth.baseUrl}/api/info").status)
        val error = server(MockOpenCodeScenario.ServerError)
        assertEquals(HttpStatusCode.InternalServerError, error.client.get("${error.baseUrl}/api/info").status)
    }

    @Test fun catalogUsesSeparateProviderModelAndAgentEnvelopes() = runTest {
        val server = server()
        for (path in listOf("provider", "model", "agent")) {
            val body = obj(server.client.get("${server.baseUrl}/api/$path").bodyAsText())
            assertTrue(body["data"]!!.jsonArray.isNotEmpty())
            assertNotNull(body["location"])
        }
        val empty = server(MockOpenCodeScenario.NoCatalog)
        assertTrue(obj(empty.client.get("${empty.baseUrl}/api/provider").bodyAsText())["data"]!!.jsonArray.isEmpty())
    }

    private suspend fun readEvent(channel: ByteReadChannel): MockSseEvent? {
        val lines = mutableListOf<String>()
        while (true) {
            val line = channel.readUTF8Line() ?: return MockSseCodec.decode(lines.joinToString("\n")).firstOrNull()
            lines += line
            if (line.isEmpty()) {
                val event = MockSseCodec.decode(lines.joinToString("\n")).firstOrNull()
                if (event != null) return event
                lines.clear()
            }
        }
    }
}
