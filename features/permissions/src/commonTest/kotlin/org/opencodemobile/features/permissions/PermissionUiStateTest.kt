package org.opencodemobile.features.permissions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.opencodemobile.shared.application.permission.PermissionState
import org.opencodemobile.shared.domain.permission.PermissionCapabilities
import org.opencodemobile.shared.domain.permission.PermissionDecision
import org.opencodemobile.shared.domain.permission.PermissionRequest

class PermissionUiStateTest {

    private val request = PermissionRequest(
        id = "per_mock_0001",
        sessionId = "ses_mock_0001",
        tool = "bash",
        patterns = listOf("rm -rf build"),
        rawArguments = """{"command":"rm -rf build"}""",
        rememberScopes = listOf("bash:rm"),
        capabilities = PermissionCapabilities.fromServer(listOf("bash:rm")),
    )

    @Test
    fun bannerRendersTheServerPayloadVerbatimAndTheExactDecisions() {
        val ui = PermissionUiState.from(PermissionState(pending = listOf(request)))

        val banner = ui.banner
        assertEquals("per_mock_0001", banner?.requestId)
        assertEquals("bash", banner?.tool)
        assertEquals(listOf("rm -rf build"), banner?.targets)
        assertEquals("""{"command":"rm -rf build"}""", banner?.argumentsText)
        assertEquals(
            request.contentFingerprint,
            banner?.contentFingerprint,
            "the banner must carry the fingerprint of what it rendered (N3)",
        )

        val decisions = banner?.decisions.orEmpty()
        assertEquals(
            listOf(
                PermissionDecision.Once,
                PermissionDecision.Deny,
                PermissionDecision.Remember,
            ),
            decisions.map { it.decision },
        )
        assertEquals(
            listOf("Allow once", "Deny", "Allow session"),
            decisions.map { it.label },
        )
        assertEquals(
            listOf(
                PermissionEmphasis.Primary,
                PermissionEmphasis.Danger,
                PermissionEmphasis.Ghost,
            ),
            decisions.map { it.emphasis },
        )
        assertEquals(
            listOf(true, false, true),
            decisions.map { it.requiresAuthentication },
        )
    }

    @Test
    fun onceOnlyServerShowsNeitherDenyNorRemember() {
        val onceOnly = request.copy(capabilities = PermissionCapabilities.OnceOnly)

        val ui = PermissionUiState.from(PermissionState(pending = listOf(onceOnly)))

        assertEquals(
            listOf(PermissionDecision.Once),
            ui.banner?.decisions?.map { it.decision },
        )
    }

    @Test
    fun displayStripsBidiFromToolAndTargetsButKeepsArgumentsExact() {
        val hostile = request.copy(
            tool = "ba\u202Esh",
            patterns = listOf("rm -rf \u200Bbuild"),
        )

        val banner = PermissionUiState.from(PermissionState(pending = listOf(hostile))).banner

        assertEquals("bash", banner?.tool)
        assertEquals(listOf("rm -rf build"), banner?.targets)
        assertEquals(
            """{"command":"rm -rf build"}""",
            banner?.argumentsText,
            "the exact server arguments are shown byte-for-byte",
        )
    }

    @Test
    fun bannerIsAbsentWhenNothingIsPending() {
        val ui = PermissionUiState.from(PermissionState())

        assertNull(ui.banner)
        assertFalse(ui.confirmationOpen)
    }

    @Test
    fun confirmationIsOpenOnlyForTheArmedRequest() {
        val ui = PermissionUiState.from(
            PermissionState(
                pending = listOf(request),
                foregrounded = true,
                armedRequestId = request.id,
                armedFingerprint = request.contentFingerprint,
            ),
        )

        assertTrue(ui.confirmationOpen)
    }
}
