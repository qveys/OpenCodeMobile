package org.opencodemobile.shared.domain.permission

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PermissionPolicyTest {

    private val bashRequest = PermissionRequest(
        id = "per_mock_0001",
        sessionId = "ses_mock_0001",
        tool = "bash",
        patterns = listOf("rm -rf build"),
        rawArguments = """{"command":"rm -rf build"}""",
        rememberScopes = listOf("bash:rm"),
        capabilities = PermissionCapabilities.fromServer(listOf("bash:rm")),
    )

    @Test
    fun decisionsAreExactlyTheServerExposedSet() {
        assertEquals(
            listOf(PermissionDecision.Once, PermissionDecision.Deny, PermissionDecision.Remember),
            PermissionPolicy.availableDecisions(bashRequest),
        )
        assertEquals(
            listOf(PermissionDecision.Once, PermissionDecision.Deny),
            PermissionPolicy.availableDecisions(
                bashRequest.copy(capabilities = PermissionCapabilities.fromServer(emptyList())),
            ),
        )
        assertEquals(
            listOf(PermissionDecision.Once),
            PermissionPolicy.availableDecisions(
                bashRequest.copy(capabilities = PermissionCapabilities.OnceOnly),
            ),
            "a once-only server must not gain deny or remember",
        )
        assertEquals(
            listOf(PermissionDecision.Once),
            PermissionPolicy.availableDecisions(
                bashRequest.copy(
                    rememberScopes = emptyList(),
                    capabilities = PermissionCapabilities.fromServer(emptyList(), supportsDeny = false),
                ),
            ),
            "a server that does not accept reject exposes only once",
        )
    }

    @Test
    fun approvingRequiresAuthenticationAndDenyingDoesNot() {
        assertTrue(PermissionPolicy.requiresAuthentication(PermissionDecision.Once))
        assertTrue(PermissionPolicy.requiresAuthentication(PermissionDecision.Remember))
        assertFalse(PermissionPolicy.requiresAuthentication(PermissionDecision.Deny))
    }

    @Test
    fun notificationCanNeverApproveAndNeverLeaksTheCommand() {
        assertFalse(PermissionPolicy.NOTIFICATION_CAN_APPROVE)

        val notification = PermissionPolicy.notificationFor(bashRequest)

        assertEquals(
            listOf(PermissionNotificationAction.OpenConfirmation, PermissionNotificationAction.Deny),
            notification.actions,
            "the only notification actions are opening the app and the safe deny",
        )
        assertTrue(
            notification.tapRoute.startsWith(PermissionPolicy.CONFIRMATION_ROUTE_PREFIX),
            "the notification deep-links to the foreground confirmation screen",
        )
        assertTrue(notification.tapRoute.endsWith(bashRequest.id))
        for (decisionWireToken in listOf("once", "always", "reject")) {
            assertFalse(
                notification.tapRoute.contains(decisionWireToken),
                "a notification deep link must never carry a decision ($decisionWireToken)",
            )
        }
        assertFalse(
            notification.body.contains("rm -rf"),
            "the lock-screen body must not leak the exact command",
        )
    }

    @Test
    fun onceOnlyNotificationOffersNoDenyAction() {
        val onceOnly = bashRequest.copy(capabilities = PermissionCapabilities.OnceOnly)

        assertEquals(
            listOf(PermissionNotificationAction.OpenConfirmation),
            PermissionPolicy.notificationFor(onceOnly).actions,
        )
    }

    @Test
    fun fingerprintIsStablePerContentAndChangesWithIt() {
        val same = bashRequest.copy()
        assertEquals(bashRequest.contentFingerprint, same.contentFingerprint)

        assertNotEquals(
            bashRequest.contentFingerprint,
            bashRequest.copy(rawArguments = """{"command":"rm -rf /"}""").contentFingerprint,
        )
        assertNotEquals(
            bashRequest.contentFingerprint,
            bashRequest.copy(patterns = listOf("rm -rf .")).contentFingerprint,
        )
    }

    @Test
    fun fingerprintDistinguishesCodePointsThatShareALowByte() {
        val plain = bashRequest.copy(rawArguments = """{"command":"rm -rf build"}""")
        val spoofed = bashRequest.copy(rawArguments = """{"command":"rm -rf \u0162uild"}""")

        assertNotEquals(
            plain.contentFingerprint,
            spoofed.contentFingerprint,
            "the digest must not mask to the low byte",
        )
    }

    @Test
    fun fingerprintCoversTheDecisionSurface() {
        assertNotEquals(
            bashRequest.contentFingerprint,
            bashRequest.copy(capabilities = PermissionCapabilities.OnceOnly).contentFingerprint,
            "a changed decision set must invalidate the armed confirmation",
        )
    }

    @Test
    fun sanitizeStripsControlAndBidiCharacters() {
        val hostile = "ba\u202Esh\u0000\u200B"

        assertEquals("bash", PermissionDisplay.sanitizeForDisplay(hostile))
    }
}
