package org.opencodemobile.features.permissions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PermissionDeepLinkTest {

    @Test
    fun opensTheConfirmationScreenForTheRequestId() {
        assertEquals(
            "per_mock_0001",
            PermissionDeepLink.confirmationRequestId(
                "opencodemobile://permission/confirmation/per_mock_0001",
            ),
        )
    }

    @Test
    fun theRoutePrefixMatchesTheNotificationPolicyRoute() {
        // A notification tap and the parser must agree on one route, or a tap would
        // silently stop opening the confirmation screen.
        assertEquals(
            PermissionDeepLink.CONFIRMATION_ROUTE_PREFIX,
            "opencodemobile://permission/confirmation/",
        )
    }

    @Test
    fun aLinkCanNeverCarryOrApplyADecision() {
        // Any attempt to smuggle a decision (query, fragment, extra segment, a
        // different host/path) is rejected outright rather than interpreted.
        val hostile = listOf(
            "opencodemobile://permission/confirmation/per_1?decision=once",
            "opencodemobile://permission/confirmation/per_1?reply=always",
            "opencodemobile://permission/confirmation/per_1#once",
            "opencodemobile://permission/confirmation/per_1/approve",
            "opencodemobile://permission/deny/per_1",
            "opencodemobile://permission/confirmation/",
            "opencodemobile://permission/confirmation",
            "opencodemobile://other/confirmation/per_1",
            "https://opencodemobile/permission/confirmation/per_1",
            "not-a-uri",
            null,
        )
        for (raw in hostile) {
            assertNull(PermissionDeepLink.confirmationRequestId(raw), "must reject: $raw")
        }
    }

    @Test
    fun theRequestIdIsASingleOpaqueSegment() {
        assertNull(PermissionDeepLink.confirmationRequestId("opencodemobile://permission/confirmation/per 1"))
        assertNull(PermissionDeepLink.confirmationRequestId("opencodemobile://permission/confirmation/per_1&deny=1"))
    }
}
