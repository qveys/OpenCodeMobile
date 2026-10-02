package org.opencodemobile.features.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.opencodemobile.shared.application.connection.ServerConnectionSetup
import org.opencodemobile.shared.application.connection.ServerSetupSource
import org.opencodemobile.shared.domain.connection.ConnectionHandshake
import org.opencodemobile.shared.domain.connection.DomainError
import org.opencodemobile.shared.domain.connection.OpenCodeGateway
import org.opencodemobile.shared.domain.connection.ServerCredential
import org.opencodemobile.shared.domain.connection.ServerFingerprint
import org.opencodemobile.shared.domain.connection.ServerHealth
import org.opencodemobile.shared.domain.connection.ServerIdentityCheck
import org.opencodemobile.shared.domain.connection.ServerIdentityException
import org.opencodemobile.shared.domain.connection.ServerNetworkScope
import org.opencodemobile.shared.domain.connection.ServerProfile
import org.opencodemobile.shared.domain.connection.ServerVersion

private class FakeGateway(
    private val onConnect: suspend (ServerProfile, ServerCredential?) -> ConnectionHandshake,
) : OpenCodeGateway {
    override suspend fun connect(profile: ServerProfile, credential: ServerCredential?): ConnectionHandshake =
        onConnect(profile, credential)

    override fun disconnect() = Unit
}

private fun fingerprint(seed: Int): ServerFingerprint =
    ServerFingerprint.of(ByteArray(32) { (it + seed).toByte() })

private fun handshake(profile: ServerProfile): ConnectionHandshake = ConnectionHandshake(
    profileId = profile.id,
    health = ServerHealth(healthy = true, version = "1.18.32"),
    identity = ServerIdentityCheck.Trusted(fingerprint(1)),
    version = ServerVersion(1, 18, 32),
    scope = ServerNetworkScope.Loopback,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionSetupControllerTest {

    @Test
    fun invalidManualEntrySetsTheInlineError() = runTest {
        val controller = ConnectionSetupController(
            ServerConnectionSetup(FakeGateway { _, _ -> error("must not connect") }),
            this,
        )

        controller.onAddressChange("  ")
        controller.submitManualEntry()

        assertNotNull(controller.state.value.manualError)
        assertFalse(controller.state.value.isReviewing)
    }

    @Test
    fun validManualEntryOpensTheReviewScreen() = runTest {
        val controller = ConnectionSetupController(
            ServerConnectionSetup(FakeGateway { _, _ -> error("must not connect") }),
            this,
        )

        controller.onAddressChange("192.168.1.10")
        controller.onLabelChange("Home")
        controller.submitManualEntry()
        advanceUntilIdle()

        val state = controller.state.value
        assertTrue(state.isReviewing)
        assertEquals("192.168.1.10", state.review?.profile?.host)
        assertEquals("Home", state.review?.profile?.label)
        assertEquals(ServerSetupSource.ManualEntry, state.review?.source)
    }

    @Test
    fun scannedImportPayloadOpensTheReviewScreen() = runTest {
        val controller = ConnectionSetupController(
            ServerConnectionSetup(FakeGateway { _, _ -> error("must not connect") }),
            this,
        )

        controller.submitScannedPayload("opencodemobile://import?host=host&port=4096")
        advanceUntilIdle()

        val state = controller.state.value
        assertTrue(state.isReviewing)
        assertEquals(ServerSetupSource.QrCode, state.review?.source)
    }

    @Test
    fun payloadThatIsNotAnImportLinkIsFlaggedNotOpened() = runTest {
        val controller = ConnectionSetupController(
            ServerConnectionSetup(FakeGateway { _, _ -> error("must not connect") }),
            this,
        )

        controller.submitScannedPayload("https://example.com")

        assertTrue(controller.state.value.scanFailed)
        assertFalse(controller.state.value.isReviewing)
    }

    @Test
    fun confirmingTheReviewConnectsAndClearsTheReview() = runTest {
        val gateway = FakeGateway { target, _ -> handshake(target) }
        val controller = ConnectionSetupController(ServerConnectionSetup(gateway), this)

        controller.onAddressChange("192.168.1.10")
        controller.submitManualEntry()
        advanceUntilIdle()
        controller.confirmReview()
        advanceUntilIdle()

        val state = controller.state.value
        assertNotNull(state.connected)
        assertFalse(state.isReviewing)
        assertNull(state.failure)
    }

    @Test
    fun firstContactPromptsIdentityAndASecondAttemptConnects() = runTest {
        val presented = fingerprint(7)
        var attempts = 0
        val gateway = FakeGateway { target, _ ->
            attempts++
            if (attempts == 1) throw ServerIdentityException.ConfirmationRequired(presented)
            handshake(target)
        }
        val confirmed = mutableListOf<ServerFingerprint>()
        val controller = ConnectionSetupController(
            setup = ServerConnectionSetup(gateway),
            scope = this,
            identityConfirmer = { _, pin -> confirmed += pin },
        )

        controller.onAddressChange("192.168.1.10")
        controller.submitManualEntry()
        advanceUntilIdle()
        controller.confirmReview()
        advanceUntilIdle()

        assertEquals(presented, controller.state.value.identityPrompt)
        assertNull(controller.state.value.connected)

        controller.confirmIdentity()
        advanceUntilIdle()

        assertEquals(listOf(presented), confirmed)
        assertNotNull(controller.state.value.connected)
    }

    @Test
    fun refusedConnectionSurfacesATypedFailureMessage() = runTest {
        val gateway = FakeGateway { _, _ -> throw IllegalStateException("boom") }
        val controller = ConnectionSetupController(ServerConnectionSetup(gateway), this)

        controller.onAddressChange("192.168.1.10")
        controller.submitManualEntry()
        advanceUntilIdle()
        controller.confirmReview()
        advanceUntilIdle()

        assertNotNull(controller.state.value.failure)
        assertNull(controller.state.value.connected)
    }

    @Test
    fun aStoredProfileWithTheSameAddressSwitchesToUpdateView() = runTest {
        val stored = ServerProfile(
            id = "192.168.1.10:4096",
            host = "192.168.1.10",
            port = 4096,
            label = "Home",
        )
        val controller = ConnectionSetupController(
            setup = ServerConnectionSetup(FakeGateway { _, _ -> error("must not connect") }),
            scope = this,
            existingProfileProvider = { stored },
        )

        controller.onAddressChange("192.168.1.10")
        controller.submitManualEntry()
        advanceUntilIdle()

        assertTrue(controller.state.value.updatesExistingProfile)
    }

    @Test
    fun cancellingTheReviewKeepsNothingPending() = runTest {
        val controller = ConnectionSetupController(
            ServerConnectionSetup(FakeGateway { _, _ -> error("must not connect") }),
            this,
        )

        controller.onAddressChange("192.168.1.10")
        controller.submitManualEntry()
        advanceUntilIdle()
        controller.cancelReview()

        assertFalse(controller.state.value.isReviewing)
        assertNull(controller.state.value.existingProfile)
    }

    @Test
    fun aSecondScannedPayloadWhileReviewingIsDiscarded() = runTest {
        val controller = ConnectionSetupController(
            ServerConnectionSetup(FakeGateway { _, _ -> error("must not connect") }),
            this,
        )

        controller.submitScannedPayload("opencodemobile://import?host=first&port=4096")
        advanceUntilIdle()
        controller.submitScannedPayload("opencodemobile://import?host=second&port=4096")
        advanceUntilIdle()

        // One pending import at a time: the first stays, the second is discarded.
        assertEquals("first", controller.state.value.review?.profile?.host)
    }

    @Test
    fun aCredentialProviderFailureIsMappedToTheFailureState() = runTest {
        val controller = ConnectionSetupController(
            setup = ServerConnectionSetup(FakeGateway { target, _ -> handshake(target) }),
            scope = this,
            credentialProvider = { throw DomainError.StorageFailure("secure store unavailable") },
        )

        controller.onAddressChange("192.168.1.10")
        controller.submitManualEntry()
        advanceUntilIdle()
        controller.confirmReview()
        advanceUntilIdle()

        assertNotNull(controller.state.value.failure)
        assertNull(controller.state.value.connected)
    }

    @Test
    fun anExistingProfileLookupFailureIsMappedToTheFailureState() = runTest {
        val controller = ConnectionSetupController(
            setup = ServerConnectionSetup(FakeGateway { _, _ -> error("must not connect") }),
            scope = this,
            existingProfileProvider = { throw DomainError.StorageFailure("secure store unavailable") },
        )

        controller.onAddressChange("192.168.1.10")
        controller.submitManualEntry()
        advanceUntilIdle()

        assertNotNull(controller.state.value.failure)
        assertFalse(controller.state.value.isReviewing)
    }

    @Test
    fun aSuccessfulConnectionNotifiesTheCompositionRoot() = runTest {
        val connected = mutableListOf<ConnectionHandshake>()
        val controller = ConnectionSetupController(
            setup = ServerConnectionSetup(FakeGateway { target, _ -> handshake(target) }),
            scope = this,
            onConnected = { connected += it },
        )

        controller.onAddressChange("192.168.1.10")
        controller.submitManualEntry()
        advanceUntilIdle()
        controller.confirmReview()
        advanceUntilIdle()

        assertEquals(1, connected.size)
        assertEquals(controller.state.value.connected, connected.single())
    }

    @Test
    fun theUpdateViewSurfacesThePinnedFingerprint() = runTest {
        val stored = ServerProfile(id = "192.168.1.10:4096", host = "192.168.1.10", port = 4096)
        val pinned = fingerprint(3)
        val controller = ConnectionSetupController(
            setup = ServerConnectionSetup(FakeGateway { _, _ -> error("must not connect") }),
            scope = this,
            existingProfileProvider = { stored },
            existingFingerprintProvider = { pinned },
        )

        controller.onAddressChange("192.168.1.10")
        controller.submitManualEntry()
        advanceUntilIdle()

        assertTrue(controller.state.value.updatesExistingProfile)
        assertEquals(pinned, controller.state.value.existingFingerprint)
    }
}
