package org.opencodemobile.shared.security.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.opencodemobile.shared.domain.connection.DomainError
import org.opencodemobile.shared.domain.connection.ServerProfile

class SecureStoreDomainErrorTest {

    @Test
    fun keyUnavailableMapsToARetryableStorageFailure() {
        val storage = assertIs<DomainError.StorageFailure>(
            SecureStoreException.KeyUnavailable("keystore key missing").toDomainError(),
        )

        assertEquals("keystore key missing", storage.detail)
        assertTrue(storage.isRetryable)
    }

    @Test
    fun corruptedEntryMapsToStorageFailure() {
        assertIs<DomainError.StorageFailure>(
            SecureStoreException.CorruptedEntry("server_credential:p1", "boom").toDomainError(),
        )
    }

    @Test
    fun aCredentialReadFailureSurfacesAsStorageFailure() = runTest {
        val credentials = SecureServerCredentialStore(FailingSecureStore())

        val failure = runCatching { credentials.credential("p1") }.exceptionOrNull()

        assertIs<DomainError.StorageFailure>(failure)
    }

    @Test
    fun aProfileWriteFailureSurfacesAsStorageFailure() = runTest {
        val profiles = SecureServerProfileStore(FailingSecureStore())

        val failure = runCatching {
            profiles.save(ServerProfile(id = "p1", host = "one.local", port = 4096))
        }.exceptionOrNull()

        assertIs<DomainError.StorageFailure>(failure)
    }
}

private class FailingSecureStore : SecureStore {
    override val keyDescriptor: SecureKeyDescriptor = SecureKeyDescriptor(
        alias = "failing",
        algorithm = "none",
        hardwareBacked = false,
    )

    override suspend fun get(key: String): String? = unavailable()

    override suspend fun put(key: String, value: String): Unit = unavailable()

    override suspend fun remove(key: String): Unit = unavailable()

    override suspend fun destroy(): Unit = unavailable()

    private fun unavailable(): Nothing =
        throw SecureStoreException.KeyUnavailable("secure store unavailable")
}
