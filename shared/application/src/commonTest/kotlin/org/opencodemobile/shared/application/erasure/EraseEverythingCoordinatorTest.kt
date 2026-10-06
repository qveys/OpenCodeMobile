package org.opencodemobile.shared.application.erasure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.opencodemobile.shared.application.notification.LocalNotificationCoordinator
import org.opencodemobile.shared.domain.cache.LocalCacheEraser
import org.opencodemobile.shared.domain.connection.ConnectionHandshake
import org.opencodemobile.shared.domain.connection.OpenCodeGateway
import org.opencodemobile.shared.domain.connection.ServerCredential
import org.opencodemobile.shared.domain.connection.ServerCredentialStore
import org.opencodemobile.shared.domain.connection.ServerFingerprint
import org.opencodemobile.shared.domain.connection.ServerIdentityStore
import org.opencodemobile.shared.domain.connection.ServerProfile
import org.opencodemobile.shared.domain.connection.ServerProfileStore
import org.opencodemobile.shared.domain.notification.AppNotification
import org.opencodemobile.shared.domain.notification.LocalNotificationSink

/**
 * "Stores vides" proof for "Tout effacer" (OPE-275 / ADR 0009 §2.5).
 *
 * Each test seeds the real-shaped stores and asserts they are empty after
 * [EraseEverythingCoordinator.erase], that the cache, the notification surface
 * and the live session were reached, and that a failure is reported instead of
 * hidden.
 */
class EraseEverythingCoordinatorTest {

    private val profileId = "profile-1"
    private val profile = ServerProfile(id = profileId, host = "server.example", port = 8443)

    @Test
    fun `erase empties every store and reports every category`() = runTest {
        val gateway = FakeGateway()
        val profileStore = FakeProfileStore(profile)
        val credentialStore = FakeCredentialStore(
            mutableMapOf(profileId to ServerCredential("secret-token")),
        )
        val identityStore = FakeIdentityStore(
            mutableMapOf(profileId to ServerFingerprint.of(ByteArray(32) { 1 })),
        )
        val cacheEraser = FakeCacheEraser()
        val sink = RecordingSink()
        val coordinator = EraseEverythingCoordinator(
            gateway = gateway,
            profileStore = profileStore,
            credentialStore = credentialStore,
            identityStore = identityStore,
            cacheEraser = cacheEraser,
            notifications = LocalNotificationCoordinator(sink),
        )

        val report = coordinator.erase()

        assertTrue(report.complete, "expected a complete erase, got failures ${report.failures}")
        assertEquals(ErasedCategory.entries.toSet(), report.erased)

        // F1: the live session is closed, so the erased credential cannot keep
        // authorising requests from the process heap.
        assertEquals(1, gateway.disconnectCalls, "the live session must be disconnected")
        assertEquals(null, profileStore.profile, "the profile must be gone")
        assertTrue(credentialStore.entries.isEmpty(), "the credential must be gone")
        assertTrue(identityStore.pins.isEmpty(), "the identity pin must be gone")
        assertEquals(1, cacheEraser.calls, "the cache must be erased exactly once")
        assertEquals(1, sink.cancelAllCalls, "the notification surface must be cleared")
    }

    @Test
    fun `erase reports a residue but still erases the other stores`() = runTest {
        val gateway = FakeGateway()
        val profileStore = FakeProfileStore(profile)
        val credentialStore = FakeCredentialStore(
            mutableMapOf(profileId to ServerCredential("secret-token")),
            failOnClear = true,
        )
        val identityStore = FakeIdentityStore(
            mutableMapOf(profileId to ServerFingerprint.of(ByteArray(32) { 2 })),
        )
        val cacheEraser = FakeCacheEraser()
        val sink = RecordingSink()
        val coordinator = EraseEverythingCoordinator(
            gateway = gateway,
            profileStore = profileStore,
            credentialStore = credentialStore,
            identityStore = identityStore,
            cacheEraser = cacheEraser,
            notifications = LocalNotificationCoordinator(sink),
        )

        val report = coordinator.erase()

        assertFalse(report.complete)
        assertEquals(listOf(ErasedCategory.Credentials), report.failures.map { it.category })
        // The failure must not stop the other categories: an erase never leaves
        // more residue than necessary.
        assertTrue(ErasedCategory.Cache in report.erased)
        assertTrue(ErasedCategory.Notifications in report.erased)
        assertEquals(1, gateway.disconnectCalls)
        assertEquals(null, profileStore.profile)
        assertTrue(identityStore.pins.isEmpty())
    }

    @Test
    fun `an unreadable profile never claims the credential and pin were erased`() = runTest {
        // A corrupted SecureStore entry makes load() throw. The profile id is
        // then unknown, so the coordinator must report the secret/pin as a
        // residue instead of a false success (review F2).
        val gateway = FakeGateway()
        val profileStore = FakeProfileStore(profile, failOnLoad = true)
        val credentialStore = FakeCredentialStore(
            mutableMapOf(profileId to ServerCredential("secret-token")),
        )
        val identityStore = FakeIdentityStore(
            mutableMapOf(profileId to ServerFingerprint.of(ByteArray(32) { 4 })),
        )
        val coordinator = EraseEverythingCoordinator(
            gateway = gateway,
            profileStore = profileStore,
            credentialStore = credentialStore,
            identityStore = identityStore,
            cacheEraser = FakeCacheEraser(),
            notifications = LocalNotificationCoordinator(RecordingSink()),
        )

        val report = coordinator.erase()

        assertFalse(report.complete)
        assertEquals(
            setOf(
                ErasedCategory.Profile,
                ErasedCategory.Credentials,
                ErasedCategory.IdentityPin,
            ),
            report.failures.map { it.category }.toSet(),
        )
        assertFalse(ErasedCategory.Credentials in report.erased)
        assertFalse(ErasedCategory.IdentityPin in report.erased)
        // The session is still closed even when the profile cannot be read.
        assertEquals(1, gateway.disconnectCalls)
    }

    @Test
    fun `a residue in the cache is surfaced, not swallowed`() = runTest {
        val coordinator = EraseEverythingCoordinator(
            gateway = FakeGateway(),
            profileStore = FakeProfileStore(profile),
            credentialStore = FakeCredentialStore(),
            identityStore = FakeIdentityStore(),
            cacheEraser = FakeCacheEraser(result = false),
            notifications = LocalNotificationCoordinator(RecordingSink()),
        )

        val report = coordinator.erase()

        assertFalse(report.complete)
        assertEquals(listOf(ErasedCategory.Cache), report.failures.map { it.category })
    }

    private class FakeGateway : OpenCodeGateway {
        var disconnectCalls: Int = 0
            private set

        override suspend fun connect(
            profile: ServerProfile,
            credential: ServerCredential?,
        ): ConnectionHandshake = throw UnsupportedOperationException("not used by the erase path")

        override fun disconnect() {
            disconnectCalls += 1
        }
    }

    private class FakeProfileStore(
        var profile: ServerProfile?,
        private val failOnLoad: Boolean = false,
    ) : ServerProfileStore {
        override suspend fun load(): ServerProfile? {
            if (failOnLoad) throw IllegalStateException("corrupted profile entry")
            return profile
        }

        override suspend fun save(profile: ServerProfile) { this.profile = profile }
        override suspend fun clear() { profile = null }
    }

    private class FakeCredentialStore(
        val entries: MutableMap<String, ServerCredential> = mutableMapOf(),
        private val failOnClear: Boolean = false,
    ) : ServerCredentialStore {
        override suspend fun credential(profileId: String): ServerCredential? = entries[profileId]
        override suspend fun storeCredential(profileId: String, credential: ServerCredential) {
            entries[profileId] = credential
        }

        override suspend fun clearCredential(profileId: String) {
            if (failOnClear) throw IllegalStateException("keystore unavailable")
            entries.remove(profileId)
        }
    }

    private class FakeIdentityStore(
        val pins: MutableMap<String, ServerFingerprint> = mutableMapOf(),
    ) : ServerIdentityStore {
        override suspend fun pinnedFingerprint(profileId: String): ServerFingerprint? = pins[profileId]
        override suspend fun storePinnedFingerprint(profileId: String, fingerprint: ServerFingerprint) {
            pins[profileId] = fingerprint
        }

        override suspend fun clearPinnedFingerprint(profileId: String) {
            pins.remove(profileId)
        }
    }

    private class FakeCacheEraser(
        private val result: Boolean = true,
    ) : LocalCacheEraser {
        var calls: Int = 0
            private set

        override suspend fun eraseLocalCache(): Boolean {
            calls += 1
            return result
        }
    }

    private class RecordingSink : LocalNotificationSink {
        private val stillPosted = mutableListOf<AppNotification>()
        var cancelAllCalls: Int = 0
            private set

        override suspend fun post(notification: AppNotification) {
            stillPosted += notification
        }

        override suspend fun cancel(id: String) {
            stillPosted.removeAll { it.id == id }
        }

        override suspend fun cancelAll() {
            cancelAllCalls += 1
            stillPosted.clear()
        }
    }
}
