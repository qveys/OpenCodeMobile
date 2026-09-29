package org.opencodemobile.shared.application.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.opencodemobile.shared.domain.connection.ConnectionHandshake
import org.opencodemobile.shared.domain.connection.OpenCodeGateway
import org.opencodemobile.shared.domain.connection.ServerAddressError
import org.opencodemobile.shared.domain.connection.ServerCredential
import org.opencodemobile.shared.domain.connection.ServerFingerprint
import org.opencodemobile.shared.domain.connection.ServerHealth
import org.opencodemobile.shared.domain.connection.ServerIdentityCheck
import org.opencodemobile.shared.domain.connection.ServerIdentityException
import org.opencodemobile.shared.domain.connection.ServerProfile

private class FakeGateway(
    private val onConnect: suspend (ServerProfile, ServerCredential?) -> ConnectionHandshake,
) : OpenCodeGateway {
    var connectCount: Int = 0
        private set

    override suspend fun connect(profile: ServerProfile, credential: ServerCredential?): ConnectionHandshake {
        connectCount++
        return onConnect(profile, credential)
    }

    override fun disconnect() = Unit
}

private fun fingerprint(seed: Int): ServerFingerprint =
    ServerFingerprint.of(ByteArray(32) { (it + seed).toByte() })

private fun handshake(profile: ServerProfile): ConnectionHandshake = ConnectionHandshake(
    profileId = profile.id,
    health = ServerHealth(healthy = true, version = "1.18.32"),
    identity = ServerIdentityCheck.Trusted(fingerprint(1)),
)

class ServerConnectionSetupTest {

    @Test
    fun manualEntryProducesReadyPlan() {
        val setup = ServerConnectionSetup(FakeGateway { _, _ -> error("must not connect") })
        val planning = setup.planManualEntry("192.168.1.10")
        val plan = assertIs<ServerSetupPlanning.Ready>(planning).plan
        assertEquals(ServerSetupSource.ManualEntry, plan.source)
        assertEquals("192.168.1.10:4096", plan.authority)
    }

    @Test
    fun plaintextProfileIsFlaggedOnThePlan() {
        val setup = ServerConnectionSetup(FakeGateway { _, _ -> error("must not connect") })
        val plan = assertIs<ServerSetupPlanning.Ready>(setup.planManualEntry("http://192.168.1.10")).plan
        assertTrue(plan.isPlaintext)
    }

    @Test
    fun invalidManualEntryIsReported() {
        val setup = ServerConnectionSetup(FakeGateway { _, _ -> error("must not connect") })
        val planning = setup.planManualEntry("   ")
        assertEquals(ServerAddressError.Blank, assertIs<ServerSetupPlanning.Invalid>(planning).reason)
    }

    @Test
    fun importLinkProducesReadyPlan() {
        val setup = ServerConnectionSetup(FakeGateway { _, _ -> error("must not connect") })
        val planning = setup.planImport("opencodemobile://import?host=host&port=4096", ServerSetupSource.QrCode)
        val plan = assertIs<ServerSetupPlanning.Ready>(planning).plan
        assertEquals(ServerSetupSource.QrCode, plan.source)
        assertEquals("host", plan.profile.host)
    }

    @Test
    fun malformedImportLinkIsDiscarded() {
        val setup = ServerConnectionSetup(FakeGateway { _, _ -> error("must not connect") })
        assertEquals(ServerSetupPlanning.Discarded, setup.planImport("not-a-link"))
    }

    @Test
    fun successfulValidationReturnsHandshake() = runTest {
        val seen = mutableListOf<ServerProfile>()
        val gateway = FakeGateway { target, _ ->
            seen += target
            handshake(target)
        }
        val setup = ServerConnectionSetup(gateway)
        val plan = assertIs<ServerSetupPlanning.Ready>(setup.planManualEntry("192.168.1.10")).plan

        val result = setup.validate(plan, credential = ServerCredential("token"))

        val connected = assertIs<ConnectionValidation.Connected>(result)
        assertEquals(true, connected.handshake.health.healthy)
        assertEquals(plan.profile.id, seen.single().id)
        assertEquals(1, gateway.connectCount)
    }

    @Test
    fun firstContactRequiresIdentityConfirmation() = runTest {
        val presented = fingerprint(7)
        val gateway = FakeGateway { _, _ -> throw ServerIdentityException.ConfirmationRequired(presented) }
        val setup = ServerConnectionSetup(gateway)
        val plan = assertIs<ServerSetupPlanning.Ready>(setup.planManualEntry("192.168.1.10")).plan

        val result = setup.validate(plan, credential = null)

        assertEquals(presented, assertIs<ConnectionValidation.IdentityConfirmationRequired>(result).presented)
    }

    @Test
    fun changedIdentityIsReported() = runTest {
        val previous = fingerprint(1)
        val presented = fingerprint(2)
        val gateway = FakeGateway { _, _ -> throw ServerIdentityException.IdentityChanged(previous, presented) }
        val setup = ServerConnectionSetup(gateway)
        val plan = assertIs<ServerSetupPlanning.Ready>(setup.planManualEntry("192.168.1.10")).plan

        val result = setup.validate(plan, credential = null)

        val changed = assertIs<ConnectionValidation.IdentityChanged>(result)
        assertEquals(previous, changed.previous)
        assertEquals(presented, changed.presented)
    }

    @Test
    fun missingCertificateIsReportedAsIdentityUnavailable() = runTest {
        val gateway = FakeGateway { _, _ -> throw ServerIdentityException.NoCertificatePresented() }
        val setup = ServerConnectionSetup(gateway)
        val plan = assertIs<ServerSetupPlanning.Ready>(setup.planManualEntry("192.168.1.10")).plan

        assertEquals(ConnectionValidation.IdentityUnavailable, setup.validate(plan, credential = null))
    }

    @Test
    fun otherHandshakeFailureIsReportedAsMessage() = runTest {
        val gateway = FakeGateway { _, _ -> throw IllegalStateException("server is unhealthy") }
        val setup = ServerConnectionSetup(gateway)
        val plan = assertIs<ServerSetupPlanning.Ready>(setup.planManualEntry("192.168.1.10")).plan

        val result = setup.validate(plan, credential = null)

        assertEquals("server is unhealthy", assertIs<ConnectionValidation.HandshakeFailed>(result).message)
    }
}
