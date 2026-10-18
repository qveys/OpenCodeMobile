package org.opencodemobile.shared.networking.permission

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.opencodemobile.shared.domain.permission.PermissionDecision
import org.opencodemobile.shared.domain.permission.PermissionEvent
import org.opencodemobile.shared.domain.permission.PermissionPolicy
import org.opencodemobile.shared.domain.permission.PermissionReplyOutcome
import org.opencodemobile.shared.testsupport.MockOpenCodeScenario
import org.opencodemobile.shared.testsupport.MockOpenCodeServer
import org.opencodemobile.shared.testsupport.OpenCodeFixtures

/**
 * V1-06 against the `permission-request` scenario of [MockOpenCodeServer]
 * (`docs/ARCHITECTURE.md` §3.3): the request is shown faithfully and each
 * decision is relayed as the exact server value.
 */
class OpenCodePermissionGatewayTest {

    @Test
    fun permissionRequestScenarioIsDecodedFaithfullyWithTheExactDecisions() = runTest {
        val server = MockOpenCodeServer(MockOpenCodeScenario.PermissionRequest).start()
        try {
            val gateway = OpenCodePermissionGateway(server.client, server.baseUrl)
            val event = OpenCodeFixtures.permissionAskedEvent()

            val decoded = gateway.decode(event.type, event.data)

            val request = (decoded as? PermissionEvent.Asked)?.request
            assertEquals(OpenCodeFixtures.PERMISSION_REQUEST_ID, request?.id)
            assertEquals("bash", request?.tool)
            assertEquals(listOf("rm -rf build"), request?.patterns)
            assertTrue(
                request?.rawArguments.orEmpty().contains("rm -rf build"),
                "the exact server arguments must be preserved: ${request?.rawArguments}",
            )
            assertEquals(
                listOf(
                    PermissionDecision.Once,
                    PermissionDecision.Deny,
                    PermissionDecision.Remember,
                ),
                PermissionPolicy.availableDecisions(request!!),
                "the server exposes three decisions for this request",
            )

            val pending = gateway.pendingPermissions()
            assertEquals(listOf(OpenCodeFixtures.PERMISSION_REQUEST_ID), pending.map { it.id })
        } finally {
            server.stop()
        }
    }

    @Test
    fun eachDecisionIsRelayedAsTheExactServerValue() = runTest {
        val server = MockOpenCodeServer(MockOpenCodeScenario.PermissionRequest).start()
        try {
            val gateway = OpenCodePermissionGateway(server.client, server.baseUrl)
            val sessionId = OpenCodeFixtures.sessions.first().id

            val outcome1 = gateway.reply(sessionId, OpenCodeFixtures.PERMISSION_REQUEST_ID, PermissionDecision.Once)
            val outcome2 = gateway.reply(sessionId, OpenCodeFixtures.PERMISSION_REQUEST_ID, PermissionDecision.Deny)
            val outcome3 = gateway.reply(sessionId, OpenCodeFixtures.PERMISSION_REQUEST_ID, PermissionDecision.Remember)

            assertEquals(PermissionReplyOutcome.Accepted, outcome1)
            assertEquals(PermissionReplyOutcome.Accepted, outcome2)
            assertEquals(PermissionReplyOutcome.Accepted, outcome3)

            assertEquals(
                listOf("once", "reject", "always"),
                server.permissionReplies.map { it.reply },
            )
            assertTrue(
                server.requests.contains(
                    "POST /api/session/$sessionId/permission/${OpenCodeFixtures.PERMISSION_REQUEST_ID}/reply",
                ),
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun repliedEventDecodesToARemovalAndUnrelatedEventsAreIgnored() {
        val gateway = OpenCodePermissionGateway(
            httpClient = io.ktor.client.HttpClient(),
            baseUrl = "http://unused.invalid",
        )

        // SSE envelope with object data
        val repliedObject = gateway.decode(
            "permission.replied",
            """{"id":"evt_1","created":12345,"type":"permission.replied","data":{"sessionID":"ses_1","requestID":"per_1","reply":"once"}}""",
        )
        assertEquals(PermissionEvent.Replied("per_1"), repliedObject)

        // SSE envelope with JSON string data
        val repliedString = gateway.decode(
            "permission.replied",
            """{"id":"evt_1","created":12345,"type":"permission.replied","data":"{\"sessionID\":\"ses_1\",\"requestID\":\"per_1\",\"reply\":\"once\"}"}""",
        )
        assertEquals(PermissionEvent.Replied("per_1"), repliedString)

        assertNull(gateway.decode("permission.asked", "not-json"))
        assertNull(gateway.decode("permission.asked", """{"id":"evt_2","type":"permission.asked","data":"not-json"}"""))
        assertNull(gateway.decode("session.updated", """{"id":"evt_3","type":"session.updated","data":{}}"""))
        assertNull(gateway.decode("server.connected", """{"id":"evt_4","type":"server.connected","data":{}}"""))
        assertNull(gateway.decode("unknown.event", """{"id":"evt_5","type":"unknown.event","data":{"id":"per_1"}}"""))
    }

    @Test
    fun askedEventWithJsonStringDataIsDecodedFaithfully() {
        val gateway = OpenCodePermissionGateway(
            httpClient = io.ktor.client.HttpClient(),
            baseUrl = "http://unused.invalid",
        )

        val askedString = gateway.decode(
            "permission.asked",
            """{"id":"evt_1","created":12345,"type":"permission.asked","data":"{\"id\":\"per_2\",\"sessionID\":\"ses_2\",\"action\":\"bash\",\"resources\":[\"echo test\"],\"save\":[\"bash:echo\"],\"metadata\":{\"cmd\":\"echo test\"}}"}""",
        )
        val request = (askedString as? PermissionEvent.Asked)?.request
        assertEquals("per_2", request?.id)
        assertEquals("ses_2", request?.sessionId)
        assertEquals("bash", request?.tool)
        assertEquals(listOf("echo test"), request?.patterns)
        assertEquals(listOf("bash:echo"), request?.rememberScopes)
        assertTrue(request?.rawArguments.orEmpty().contains("echo test"))
    }

    @Test
    fun askedEventWithoutSessionIdIsDropped() {
        val gateway = OpenCodePermissionGateway(
            httpClient = io.ktor.client.HttpClient(),
            baseUrl = "http://unused.invalid",
        )

        assertNull(
            gateway.decode(
                "permission.asked",
                """{"id":"evt_1","type":"permission.asked","data":{"id":"per_1","action":"bash","resources":["echo 1"]}}""",
            ),
        )
    }

    @Test
    fun malformedArrayElementsDoNotThrow() {
        val gateway = OpenCodePermissionGateway(
            httpClient = io.ktor.client.HttpClient(),
            baseUrl = "http://unused.invalid",
        )

        // A non-primitive element inside `resources` used to throw out of decode,
        // which would kill the SSE pipeline. It must be ignored instead.
        val decoded = gateway.decode(
            "permission.asked",
            """{"id":"evt_1","type":"permission.asked","data":{"id":"per_1","sessionID":"ses_1",""" +
                """"action":"bash","resources":[{"a":1}],"save":[],"metadata":{}}}""",
        )

        val request = (decoded as? PermissionEvent.Asked)?.request
        assertEquals("per_1", request?.id)
        assertTrue(request?.patterns?.isEmpty() == true)
    }

    @Test
    fun legacyV2PrefixEventsAreIgnored() {
        val gateway = OpenCodePermissionGateway(
            httpClient = io.ktor.client.HttpClient(),
            baseUrl = "http://unused.invalid",
        )

        assertNull(
            gateway.decode(
                "permission.v2.asked",
                """{"id":"evt_1","type":"permission.v2.asked","data":{"id":"per_1","sessionID":"ses_1","action":"bash","resources":["rm -rf build"]}}""",
            ),
        )
        assertNull(
            gateway.decode(
                "permission.v2.replied",
                """{"id":"evt_2","type":"permission.v2.replied","data":{"sessionID":"ses_1","requestID":"per_1","reply":"once"}}""",
            ),
        )
    }
}
