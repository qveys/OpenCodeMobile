package org.opencodemobile.ios

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.opencodemobile.shared.application.notification.LocalNotificationCoordinator
import org.opencodemobile.shared.data.cache.createIosCacheStack
import org.opencodemobile.shared.domain.connection.ServerFingerprint
import org.opencodemobile.shared.domain.connection.ServerIdentityStore
import org.opencodemobile.shared.domain.connection.ServerIdentityVerifier
import org.opencodemobile.shared.domain.connection.ServerProfile
import org.opencodemobile.shared.domain.event.ConnectionPhase
import org.opencodemobile.shared.domain.notification.NoOpLocalNotificationSink
import org.opencodemobile.shared.domain.permission.FailClosedBiometricAuthenticator
import org.opencodemobile.shared.networking.adapter.createOpenCodeGateway
import org.opencodemobile.shared.security.identity.TofuServerIdentityCoordinator
import org.opencodemobile.shared.security.identity.ServerIdentityGate
import org.opencodemobile.shared.security.identity.ServerIdentityPinController

@OptIn(ExperimentalCoroutinesApi::class)
class IosSessionHostTest {
    @Test
    fun bindingReplacesTheSessionGraphAndDisconnectReturnsToSetup() {
        // Keep the collectors queued: this lifecycle test never touches the network or disk.
        Dispatchers.setMain(StandardTestDispatcher())
        val adapter = createOpenCodeGateway(ServerIdentityPinController(), ServerIdentityGate(TofuServerIdentityCoordinator(EmptyIdentityStore, object : ServerIdentityVerifier {
            override suspend fun presentedFingerprint(profile: ServerProfile): ServerFingerprint =
                error("This lifecycle test must never probe a server")
        })))
        val cache = createIosCacheStack()
        val host = IosSessionHost(
            adapter, cache, FailClosedBiometricAuthenticator,
            LocalNotificationCoordinator(NoOpLocalNotificationSink),
        )
        try {
            assertNull(host.runtime.value)
            assertFalse(cache.gate.mutationsAllowed())
            host.connect(ServerProfile("first", "127.0.0.1", 4096))
            val first = assertNotNull(host.runtime.value)
            host.connect(ServerProfile("second", "127.0.0.1", 4097))
            val second = assertNotNull(host.runtime.value)
            assertNotSame(first, second)
            assertEquals(ConnectionPhase.Stopped, first.connection.value.phase)
            host.disconnect()
            assertNull(host.runtime.value)
            assertEquals(ConnectionPhase.Stopped, second.connection.value.phase)
            assertFalse(cache.gate.mutationsAllowed())
        } finally {
            host.disconnect()
            Dispatchers.resetMain()
        }
    }
}

private object EmptyIdentityStore : ServerIdentityStore {
    override suspend fun pinnedFingerprint(profileId: String): ServerFingerprint? = null
    override suspend fun storePinnedFingerprint(profileId: String, fingerprint: ServerFingerprint) = Unit
    override suspend fun clearPinnedFingerprint(profileId: String) = Unit
}
