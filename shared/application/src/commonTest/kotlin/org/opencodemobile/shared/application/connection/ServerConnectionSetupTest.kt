package org.opencodemobile.shared.application.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.opencodemobile.shared.domain.connection.ConnectionHandshake
import org.opencodemobile.shared.domain.connection.DomainError
import org.opencodemobile.shared.domain.connection.OpenCodeGateway
import org.opencodemobile.shared.domain.connection.ServerCredential
import org.opencodemobile.shared.domain.connection.ServerFingerprint
import org.opencodemobile.shared.domain.connection.ServerHealth
import org.opencodemobile.shared.domain.connection.ServerIdentityCheck
import org.opencodemobile.shared.domain.connection.ServerIdentityException
import org.opencodemobile.shared.domain.connection.ServerInputProblem
import org.opencodemobile.shared.domain.connection.ServerNetworkScope
import org.opencodemobile.shared.domain.connection.ServerProfile
import org.opencodemobile.shared.domain.connection.ServerVersion

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
    version = ServerVersion(1, 18, 32),
    scope = ServerNetworkScope.Loopback,
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
    fun manualEntryCarriesTheOptionalLabel() {
        val setup = ServerConnectionSetup(FakeGateway { _, _ -> error("must not connect") })
        val plan = assertIs<ServerSetupPlanning.Ready>(
            setup.planManualEntry("192.168.1.10", label = " Home server "),
        ).plan
        assertEquals("Home server", plan.profile.label)
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
        assertEquals(
            ServerInputProblem.BLANK,
            assertIs<ServerSetupPlanning.Invalid>(planning).error.problem,
        )
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
    fun importLinkCarriesItsFingerprint() {
        val setup = ServerConnectionSetup(FakeGateway { _, _ -> error("must not connect") })
        val payload = "opencodemobile://import?host=host&fp=" + "ab".repeat(32)
        val plan = assertIs<ServerSetupPlanning.Ready>(setup.planImport(payload)).plan
        assertEquals("ab".repeat(32), plan.fingerprint?.hex)
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
    fun firstContactIsRejectedAsIdentityUnconfirmed() = runTest {
        val presented = fingerprint(7)
        val gateway = FakeGateway { _, _ -> throw ServerIdentityException.ConfirmationRequired(presented) }
        val setup = ServerConnectionSetup(gateway)
        val plan = assertIs<ServerSetupPlanning.Ready>(setup.planManualEntry("192.168.1.10")).plan

        val result = setup.validate(plan, credential = null)

        val error = assertIs<ConnectionValidation.Rejected>(result).error
        assertEquals(presented, assertIs<DomainError.IdentityUnconfirmed>(error).presented)
    }

    @Test
    fun changedIdentityIsRejectedAsDomainError() = runTest {
        val previous = fingerprint(1)
        val presented = fingerprint(2)
        val gateway = FakeGateway { _, _ -> throw ServerIdentityException.IdentityChanged(previous, presented) }
        val setup = ServerConnectionSetup(gateway)
        val plan = assertIs<ServerSetupPlanning.Ready>(setup.planManualEntry("192.168.1.10")).plan

        val result = setup.validate(plan, credential = null)

        val error = assertIs<ConnectionValidation.Rejected>(result).error
        val changed = assertIs<DomainError.IdentityChanged>(error)
        assertEquals(previous, changed.previous)
        assertEquals(presented, changed.presented)
    }

    @Test
    fun missingCertificateIsRejectedAsIdentityNotVerifiable() = runTest {
        val gateway = FakeGateway { _, _ -> throw ServerIdentityException.NoCertificatePresented() }
        val setup = ServerConnectionSetup(gateway)
        val plan = assertIs<ServerSetupPlanning.Ready>(setup.planManualEntry("192.168.1.10")).plan

        val error = assertIs<ConnectionValidation.Rejected>(setup.validate(plan, credential = null)).error
        assertIs<DomainError.IdentityNotVerifiable>(error)
    }

    @Test
    fun otherHandshakeFailureIsRejectedAsUnknown() = runTest {
        val gateway = FakeGateway { _, _ -> throw IllegalStateException("server is unhealthy") }
        val setup = ServerConnectionSetup(gateway)
        val plan = assertIs<ServerSetupPlanning.Ready>(setup.planManualEntry("192.168.1.10")).plan

        val error = assertIs<ConnectionValidation.Rejected>(setup.validate(plan, credential = null)).error
        assertIs<DomainError.Unknown>(error)
    }
}
