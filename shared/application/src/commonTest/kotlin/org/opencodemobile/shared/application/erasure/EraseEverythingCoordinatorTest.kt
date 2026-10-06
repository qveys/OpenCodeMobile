package org.opencodemobile.shared.application.erasure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.opencodemobile.shared.application.notification.LocalNotificationCoordinator
import org.opencodemobile.shared.domain.cache.LocalCacheEraser
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
 * [EraseEverythingCoordinator.erase], that the cache and the notification
 * surface were reached, and that a failure is reported instead of hidden.
 */
class EraseEverythingCoordinatorTest {

    private val profileId = "profile-1"
    private val profile = ServerProfile(id = profileId, host = "server.example", port = 8443)

    @Test
    fun `erase empties every store and reports every category`() = runTest {
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
            profileStore = profileStore,
            credentialStore = credentialStore,
            identityStore = identityStore,
            cacheEraser = cacheEraser,
            notifications = LocalNotificationCoordinator(sink),
        )

        val report = coordinator.erase()

        assertTrue(report.complete, "expected a complete erase, got failures ${report.failures}")
        assertEquals(ErasedCategory.entries.toSet(), report.erased)

        assertEquals(null, profileStore.profile, "the profile must be gone")
        assertTrue(credentialStore.entries.isEmpty(), "the credential must be gone")
        assertTrue(identityStore.pins.isEmpty(), "the identity pin must be gone")
        assertEquals(1, cacheEraser.calls, "the cache must be erased exactly once")
        assertEquals(1, sink.cancelAllCalls, "the notification surface must be cleared")
    }

    @Test
    fun `erase reports a residue but still erases the other stores`() = runTest {
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
            profileStore = profileStore,
            credentialStore = credentialStore,
            identityStore = identityStore,
            cacheEraser = cacheEraser,
            notifications = LocalNotificationCoordinator(sink),
        )

        val report = coordinator.erase()

        assertTrue(!report.complete)
        assertEquals(listOf(ErasedCategory.Credentials), report.failures.map { it.category })
        // The failure must not stop the other categories: an erase never leaves
        // more residue than necessary.
        assertTrue(ErasedCategory.Cache in report.erased)
        assertTrue(ErasedCategory.Notifications in report.erased)
        assertEquals(null, profileStore.profile)
        assertTrue(identityStore.pins.isEmpty())
    }

    @Test
    fun `a residue in the cache is surfaced, not swallowed`() = runTest {
        val coordinator = EraseEverythingCoordinator(
            profileStore = FakeProfileStore(profile),
            credentialStore = FakeCredentialStore(),
            identityStore = FakeIdentityStore(),
            cacheEraser = FakeCacheEraser(result = false),
            notifications = LocalNotificationCoordinator(RecordingSink()),
        )

        val report = coordinator.erase()

        assertTrue(!report.complete)
        assertEquals(listOf(ErasedCategory.Cache), report.failures.map { it.category })
    }

    @Test
    fun `the coordinator touches no store until it is explicitly invoked`() = runTest {
        val profileStore = FakeProfileStore(profile)
        val credentialStore = FakeCredentialStore(
            mutableMapOf(profileId to ServerCredential("secret-token")),
        )
        val identityStore = FakeIdentityStore(
            mutableMapOf(profileId to ServerFingerprint.of(ByteArray(32) { 3 })),
        )
        val cacheEraser = FakeCacheEraser()

        // Constructing the eraser must not erase anything: the action is only
        // ever triggered by an explicit user confirmation (ADR 0009 §2.2).
        EraseEverythingCoordinator(
            profileStore = profileStore,
            credentialStore = credentialStore,
            identityStore = identityStore,
            cacheEraser = cacheEraser,
            notifications = LocalNotificationCoordinator(RecordingSink()),
        )

        assertEquals(profile, profileStore.profile)
        assertEquals(1, credentialStore.entries.size)
        assertEquals(1, identityStore.pins.size)
        assertEquals(0, cacheEraser.calls)
    }

    private class FakeProfileStore(var profile: ServerProfile?) : ServerProfileStore {
        override suspend fun load(): ServerProfile? = profile
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
