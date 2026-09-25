package org.opencodemobile.shared.security.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlinx.coroutines.test.runTest
import org.opencodemobile.shared.domain.connection.ServerFingerprint
import org.opencodemobile.shared.domain.connection.ServerIdentityCheck
import org.opencodemobile.shared.domain.connection.ServerIdentityException
import org.opencodemobile.shared.domain.connection.ServerIdentityStore
import org.opencodemobile.shared.domain.connection.ServerIdentityVerifier
import org.opencodemobile.shared.domain.connection.ServerProfile

private class FakeIdentityStore(
    private val entries: MutableMap<String, ServerFingerprint> = mutableMapOf(),
) : ServerIdentityStore {
    override suspend fun pinnedFingerprint(profileId: String): ServerFingerprint? = entries[profileId]

    override suspend fun storePinnedFingerprint(profileId: String, fingerprint: ServerFingerprint) {
        entries[profileId] = fingerprint
    }

    override suspend fun clearPinnedFingerprint(profileId: String) {
        entries.remove(profileId)
    }
}

private class FakeVerifier(private var presented: ServerFingerprint) : ServerIdentityVerifier {
    override suspend fun presentedFingerprint(profile: ServerProfile): ServerFingerprint = presented

    fun present(fingerprint: ServerFingerprint) {
        presented = fingerprint
    }
}

private fun fingerprint(seed: Int): ServerFingerprint =
    ServerFingerprint.of(ByteArray(32) { (it + seed).toByte() })

class TofuServerIdentityCoordinatorTest {

    private val profile = ServerProfile(id = "p1", host = "192.168.1.10", port = 4096)

    @Test
    fun firstContactIsReportedWithoutPinning() = runTest {
        val store = FakeIdentityStore()
        val verifier = FakeVerifier(fingerprint(1))
        val coordinator = TofuServerIdentityCoordinator(store, verifier)

        val check = coordinator.inspect(profile)

        val firstContact = assertIs<ServerIdentityCheck.FirstContact>(check)
        assertEquals(fingerprint(1), firstContact.presented)
        assertEquals(null, store.pinnedFingerprint(profile.id))
    }

    @Test
    fun pinnedMatchIsTrusted() = runTest {
        val store = FakeIdentityStore(mutableMapOf(profile.id to fingerprint(1)))
        val verifier = FakeVerifier(fingerprint(1))
        val coordinator = TofuServerIdentityCoordinator(store, verifier)

        assertEquals(
            ServerIdentityCheck.Trusted(fingerprint(1)),
            coordinator.inspect(profile),
        )
    }

    @Test
    fun changedFingerprintIsReportedAsChanged() = runTest {
        val store = FakeIdentityStore(mutableMapOf(profile.id to fingerprint(1)))
        val verifier = FakeVerifier(fingerprint(2))
        val coordinator = TofuServerIdentityCoordinator(store, verifier)

        val check = coordinator.inspect(profile)

        val changed = assertIs<ServerIdentityCheck.Changed>(check)
        assertEquals(fingerprint(1), changed.previous)
        assertEquals(fingerprint(2), changed.presented)
        assertEquals(fingerprint(1), store.pinnedFingerprint(profile.id))
    }

    @Test
    fun confirmFirstContactPinsOnlyWhenNoConflictingPinExists() = runTest {
        val store = FakeIdentityStore()
        val verifier = FakeVerifier(fingerprint(1))
        val coordinator = TofuServerIdentityCoordinator(store, verifier)

        coordinator.confirmFirstContact(profile, fingerprint(1))
        assertEquals(fingerprint(1), store.pinnedFingerprint(profile.id))

        assertFailsWith<ServerIdentityException.IdentityChanged> {
            coordinator.confirmFirstContact(profile, fingerprint(2))
        }
        assertEquals(fingerprint(1), store.pinnedFingerprint(profile.id))
    }

    @Test
    fun acceptChangedIdentityRepinsAfterExplicitReview() = runTest {
        val store = FakeIdentityStore(mutableMapOf(profile.id to fingerprint(1)))
        val verifier = FakeVerifier(fingerprint(2))
        val coordinator = TofuServerIdentityCoordinator(store, verifier)

        coordinator.acceptChangedIdentity(profile, fingerprint(2))
        assertEquals(fingerprint(2), store.pinnedFingerprint(profile.id))
        assertEquals(ServerIdentityCheck.Trusted(fingerprint(2)), coordinator.inspect(profile))
    }

    @Test
    fun gateBlocksFirstContactAndChangedUntilExplicitlyResolved() = runTest {
        val store = FakeIdentityStore()
        val verifier = FakeVerifier(fingerprint(1))
        val coordinator = TofuServerIdentityCoordinator(store, verifier)
        val gate = ServerIdentityGate(coordinator)

        assertIs<ServerIdentityAuthorization.ConfirmationRequired>(gate.authorize(profile))
        coordinator.confirmFirstContact(profile, fingerprint(1))
        assertIs<ServerIdentityAuthorization.Authorized>(gate.authorize(profile))

        verifier.present(fingerprint(2))
        val blocked = assertIs<ServerIdentityAuthorization.Blocked>(gate.authorize(profile))
        assertEquals(fingerprint(1), blocked.previous)
        assertEquals(fingerprint(2), blocked.presented)

        coordinator.acceptChangedIdentity(profile, fingerprint(2))
        assertIs<ServerIdentityAuthorization.Authorized>(gate.authorize(profile))
    }

    @Test
    fun plaintextHttpIsAuthorizedWithTextualWarning() = runTest {
        val plaintext = profile.copy(tls = ServerProfile.TlsMode.PlaintextHttp)
        val coordinator = TofuServerIdentityCoordinator(FakeIdentityStore(), FakeVerifier(fingerprint(1)))
        val gate = ServerIdentityGate(coordinator)

        val authorized = assertIs<ServerIdentityAuthorization.Authorized>(gate.authorize(plaintext))
        assertEquals(null, authorized.fingerprint)
        assertEquals(PlaintextHttpWarning.TEXT, authorized.plaintextWarning)
    }

    @Test
    fun fingerprintRendersAsColonSeparatedHex() {
        val raw = ByteArray(32) { 0x00 }
        raw[0] = 0xAB.toByte()
        raw[1] = 0x01
        val rendered = ServerFingerprint.of(raw).colonSeparated

        assertEquals("ab:01:00", rendered.substring(0, 8))
        assertEquals(rendered, ServerFingerprint.fromHex(rendered).colonSeparated)
    }

    @Test
    fun fingerprintRejectsWrongLength() {
        assertFailsWith<IllegalArgumentException> { ServerFingerprint.fromHex("abcd") }
    }

    @Test
    fun differentFingerprintsAreNotEqual() {
        assertNotEquals(fingerprint(1), fingerprint(2))
    }
}