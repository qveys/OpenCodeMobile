package org.opencodemobile.shared.networking.permission

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.opencodemobile.shared.domain.permission.PermissionDecision
import org.opencodemobile.shared.domain.permission.PermissionEvent
import org.opencodemobile.shared.domain.permission.PermissionPolicy
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

            gateway.reply(OpenCodeFixtures.PERMISSION_REQUEST_ID, PermissionDecision.Once)
            gateway.reply(OpenCodeFixtures.PERMISSION_REQUEST_ID, PermissionDecision.Deny)
            gateway.reply(OpenCodeFixtures.PERMISSION_REQUEST_ID, PermissionDecision.Remember)

            assertEquals(
                listOf("once", "reject", "always"),
                server.permissionReplies.map { it.reply },
            )
            assertTrue(
                server.requests.contains(
                    "POST /permission/${OpenCodeFixtures.PERMISSION_REQUEST_ID}/reply",
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

        val replied = gateway.decode(
            "permission.replied",
            """{"type":"permission.replied","properties":{"sessionID":"ses_1","requestID":"per_1","reply":"once"}}""",
        )
        assertEquals(PermissionEvent.Replied("per_1"), replied)

        assertNull(gateway.decode("permission.asked", "not-json"))
        assertNull(gateway.decode("session.updated", """{"type":"session.updated","properties":{}}"""))
    }
}
