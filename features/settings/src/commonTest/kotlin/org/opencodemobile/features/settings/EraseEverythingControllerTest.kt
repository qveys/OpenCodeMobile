package org.opencodemobile.features.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.opencodemobile.shared.application.erasure.EraseEverythingCoordinator
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
import org.opencodemobile.shared.domain.localaccess.LocalAccessSettings
import org.opencodemobile.shared.domain.localaccess.LocalAccessSettingsStore
import org.opencodemobile.shared.domain.permission.BiometricAuthenticator
import org.opencodemobile.shared.domain.permission.BiometricResult

/**
 * Proves the "Tout effacer" action is never silent and never automatic
 * (OPE-275 / ADR 0009 §2.2): it runs only after an explicit confirmation, and
 * the optional biometric gate can stop it without erasing anything.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EraseEverythingControllerTest {

    private val profileId = "profile-1"
    private val profile = ServerProfile(id = profileId, host = "server.example", port = 8443)

    @Test
    fun `confirm without a request erases nothing`() = runTest {
        val harness = Harness(scope = this)

        harness.controller.confirmErase()
        advanceUntilIdle()

        assertEquals(EraseEverythingUiState.Idle, harness.controller.state.value)
        assertEquals(0, harness.cacheEraser.calls)
        assertTrue(harness.profileStore.profile != null)
        assertTrue(harness.credentialStore.entries.isNotEmpty())
    }

    @Test
    fun `request then confirm erases every store`() = runTest {
        val harness = Harness(scope = this)

        harness.controller.requestErase()
        assertEquals(EraseEverythingUiState.Confirming, harness.controller.state.value)
        harness.controller.confirmErase()
        advanceUntilIdle()

        assertIs<EraseEverythingUiState.Erased>(harness.controller.state.value)
        assertEquals(null, harness.profileStore.profile)
        assertTrue(harness.credentialStore.entries.isEmpty())
        assertTrue(harness.identityStore.pins.isEmpty())
        assertEquals(1, harness.cacheEraser.calls)
    }

    @Test
    fun `cancelling the confirmation erases nothing`() = runTest {
        val harness = Harness(scope = this)

        harness.controller.requestErase()
        harness.controller.cancelErase()
        advanceUntilIdle()

        assertEquals(EraseEverythingUiState.Idle, harness.controller.state.value)
        assertEquals(0, harness.cacheEraser.calls)
        assertTrue(harness.credentialStore.entries.isNotEmpty())
    }

    @Test
    fun `an enabled biometric gate that is cancelled stops the erase`() = runTest {
        val harness = Harness(
            scope = this,
            biometricEnabled = true,
            biometricResult = BiometricResult.Cancelled,
        )

        harness.controller.requestErase()
        harness.controller.confirmErase()
        advanceUntilIdle()

        assertIs<EraseEverythingUiState.ReauthenticationFailed>(harness.controller.state.value)
        assertEquals(0, harness.cacheEraser.calls)
        assertTrue(harness.credentialStore.entries.isNotEmpty())
    }

    @Test
    fun `an enabled but unavailable biometric gate still allows the erase`() = runTest {
        val harness = Harness(
            scope = this,
            biometricEnabled = true,
            biometricResult = BiometricResult.Unavailable,
        )

        harness.controller.requestErase()
        harness.controller.confirmErase()
        advanceUntilIdle()

        // Biometrics are optional (ADR 0009 §2.3): not enrolled must not block.
        assertIs<EraseEverythingUiState.Erased>(harness.controller.state.value)
        assertTrue(harness.credentialStore.entries.isEmpty())
    }

    private class Harness(
        scope: kotlinx.coroutines.CoroutineScope,
        biometricEnabled: Boolean = false,
        biometricResult: BiometricResult = BiometricResult.Succeeded,
    ) {
        val profileStore = FakeProfileStore(
            ServerProfile(id = "profile-1", host = "server.example", port = 8443),
        )
        val credentialStore = FakeCredentialStore(
            mutableMapOf("profile-1" to ServerCredential("secret-token")),
        )
        val identityStore = FakeIdentityStore(
            mutableMapOf("profile-1" to ServerFingerprint.of(ByteArray(32) { 7 })),
        )
        val cacheEraser = FakeCacheEraser()
        private val sink = RecordingSink()
        private val coordinator = EraseEverythingCoordinator(
            gateway = FakeGateway(),
            profileStore = profileStore,
            credentialStore = credentialStore,
            identityStore = identityStore,
            cacheEraser = cacheEraser,
            notifications = LocalNotificationCoordinator(sink),
        )
        val controller = EraseEverythingController(
            coordinator = coordinator,
            settingsStore = FakeSettingsStore(biometricEnabled),
            biometric = FakeBiometric(biometricResult),
            scope = scope,
        )
    }

    private class FakeProfileStore(var profile: ServerProfile?) : ServerProfileStore {
        override suspend fun load(): ServerProfile? = profile
        override suspend fun save(profile: ServerProfile) { this.profile = profile }
        override suspend fun clear() { profile = null }
    }

    private class FakeCredentialStore(
        val entries: MutableMap<String, ServerCredential> = mutableMapOf(),
    ) : ServerCredentialStore {
        override suspend fun credential(profileId: String): ServerCredential? = entries[profileId]
        override suspend fun storeCredential(profileId: String, credential: ServerCredential) {
            entries[profileId] = credential
        }

        override suspend fun clearCredential(profileId: String) { entries.remove(profileId) }
    }

    private class FakeIdentityStore(
        val pins: MutableMap<String, ServerFingerprint> = mutableMapOf(),
    ) : ServerIdentityStore {
        override suspend fun pinnedFingerprint(profileId: String): ServerFingerprint? = pins[profileId]
        override suspend fun storePinnedFingerprint(profileId: String, fingerprint: ServerFingerprint) {
            pins[profileId] = fingerprint
        }

        override suspend fun clearPinnedFingerprint(profileId: String) { pins.remove(profileId) }
    }

    private class FakeCacheEraser : LocalCacheEraser {
        var calls: Int = 0
            private set

        override suspend fun eraseLocalCache(): Boolean {
            calls += 1
            return true
        }
    }

    private class FakeGateway : OpenCodeGateway {
        override suspend fun connect(
            profile: ServerProfile,
            credential: ServerCredential?,
        ): ConnectionHandshake = throw UnsupportedOperationException("not used by the erase path")

        override fun disconnect() = Unit
    }

    private class FakeSettingsStore(private val enabled: Boolean) : LocalAccessSettingsStore {
        override fun load(): LocalAccessSettings =
            LocalAccessSettings(optionalBiometricsEnabled = enabled)

        override fun save(settings: LocalAccessSettings) = Unit
    }

    private class FakeBiometric(private val result: BiometricResult) : BiometricAuthenticator {
        override suspend fun authenticate(reason: String): BiometricResult = result
    }

    private class RecordingSink : org.opencodemobile.shared.domain.notification.LocalNotificationSink {
        override suspend fun post(notification: org.opencodemobile.shared.domain.notification.AppNotification) = Unit
        override suspend fun cancel(id: String) = Unit
        override suspend fun cancelAll() = Unit
    }
}
