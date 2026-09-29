package org.opencodemobile.shared.security.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.opencodemobile.shared.domain.connection.DomainError
import org.opencodemobile.shared.domain.connection.ServerCredential

class SecureServerCredentialStoreTest {

    private val profileId = "profile-1"
    private val credential = ServerCredential("s3cr3t-bearer-token")

    @Test
    fun storedCredentialRoundTripsAndIsHiddenFromTheRawKeyLayout() = runTest {
        val store = InMemorySecureStore()
        val credentials = SecureServerCredentialStore(store)

        credentials.storeCredential(profileId, credential)

        assertEquals(credential.bearerToken, credentials.credential(profileId)?.bearerToken)
        assertTrue(store.entries.containsKey("server_credential:$profileId"))
    }

    @Test
    fun absentCredentialReadsAsNull() = runTest {
        val credentials = SecureServerCredentialStore(InMemorySecureStore())

        assertNull(credentials.credential(profileId))
    }

    @Test
    fun credentialsAreIsolatedPerProfile() = runTest {
        val credentials = SecureServerCredentialStore(InMemorySecureStore())
        val other = ServerCredential("other-token")

        credentials.storeCredential("p1", credential)
        credentials.storeCredential("p2", other)

        assertEquals(credential.bearerToken, credentials.credential("p1")?.bearerToken)
        assertEquals(other.bearerToken, credentials.credential("p2")?.bearerToken)
    }

    @Test
    fun clearRemovesOnlyTheTargetProfile() = runTest {
        val credentials = SecureServerCredentialStore(InMemorySecureStore())
        credentials.storeCredential("p1", credential)
        credentials.storeCredential("p2", ServerCredential("other-token"))

        credentials.clearCredential("p1")

        assertNull(credentials.credential("p1"))
        assertEquals("other-token", credentials.credential("p2")?.bearerToken)
    }

    @Test
    fun blankProfileIdIsRejected() = runTest {
        val credentials = SecureServerCredentialStore(InMemorySecureStore())

        assertFailsWith<IllegalArgumentException> {
            credentials.storeCredential("  ", credential)
        }
    }

    @Test
    fun unversionedStoredValueFailsClosed() = runTest {
        val store = InMemorySecureStore()
        store.put("server_credential:$profileId", "raw-token-without-envelope")
        val credentials = SecureServerCredentialStore(store)

        assertFailsWith<DomainError.StorageFailure> {
            credentials.credential(profileId)
        }
    }
}
