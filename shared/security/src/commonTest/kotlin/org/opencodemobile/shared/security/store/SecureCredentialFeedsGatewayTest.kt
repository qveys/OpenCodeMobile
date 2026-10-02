package org.opencodemobile.shared.security.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import org.opencodemobile.shared.domain.connection.ConnectionHandshake
import org.opencodemobile.shared.domain.connection.OpenCodeGateway
import org.opencodemobile.shared.domain.connection.ServerCredential
import org.opencodemobile.shared.domain.connection.ServerProfile

/** Sentinel thrown by the probe so the test never has to build a handshake value. */
private class GatewayProbe : RuntimeException()

private class RecordingGateway : OpenCodeGateway {
    var connectedProfile: ServerProfile? = null
    var releasedCredential: ServerCredential? = null

    override suspend fun connect(
        profile: ServerProfile,
        credential: ServerCredential?,
    ): ConnectionHandshake {
        connectedProfile = profile
        releasedCredential = credential
        throw GatewayProbe()
    }

    override fun disconnect() = Unit
}

/**
 * Proves the integration point with OpenCode Server v2 authentication: the
 * credential read back from the encrypted store is the exact value released to
 * the adapter's `connect`. The adapter itself performs the T1 identity gate
 * before attaching the credential; that gate is covered by the T1 tests.
 */
class SecureCredentialFeedsGatewayTest {

    @Test
    fun credentialLoadedFromSecureStoreIsReleasedToTheGateway() = runTest {
        val credentials = SecureServerCredentialStore(InMemorySecureStore())
        val profile = ServerProfile(id = "p1", host = "opencode.local", port = 4096)
        val token = ServerCredential("v2-auth-token")
        credentials.storeCredential(profile.id, token)

        val gateway = RecordingGateway()
        assertFailsWith<GatewayProbe> {
            gateway.connect(profile, credentials.credential(profile.id))
        }

        assertEquals(profile, gateway.connectedProfile)
        assertEquals(token.bearerToken, gateway.releasedCredential?.bearerToken)
    }
}
