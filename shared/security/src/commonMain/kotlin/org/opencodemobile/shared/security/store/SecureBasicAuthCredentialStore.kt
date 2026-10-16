package org.opencodemobile.shared.security.store

import org.opencodemobile.shared.domain.connection.BasicAuthCredential
import org.opencodemobile.shared.domain.connection.BasicAuthCredentialStore

/**
 * [BasicAuthCredentialStore] backed by an encrypted [SecureStore]. Mirrors
 * [SecureServerCredentialStore] exactly, in its own key prefix/namespace so the
 * reverse-proxy credential cannot be read across stores (T8/T1).
 */
public class SecureBasicAuthCredentialStore(
    private val store: SecureStore,
    private val keyPrefix: String = DEFAULT_KEY_PREFIX,
) : BasicAuthCredentialStore {

    override suspend fun credential(profileId: String): BasicAuthCredential? =
        translate { store.get(entryKey(profileId))?.let(::decode) }

    override suspend fun storeCredential(profileId: String, credential: BasicAuthCredential) {
        translate { store.put(entryKey(profileId), encode(credential)) }
    }

    override suspend fun clearCredential(profileId: String) {
        translate { store.remove(entryKey(profileId)) }
    }

    /**
     * Surfaces any [SecureStoreException] as the typed, retryable
     * `DomainError.StorageFailure` the connection layer handles (OPE-145).
     */
    private suspend fun <T> translate(block: suspend () -> T): T =
        try {
            block()
        } catch (failure: SecureStoreException) {
            throw failure.toDomainError()
        }

    private fun entryKey(profileId: String): String {
        require(profileId.isNotBlank()) { "A Basic Auth credential must be keyed by a non-blank profile id" }
        return "$keyPrefix:$profileId"
    }

    private fun encode(credential: BasicAuthCredential): String =
        "$FORMAT_V1\n${credential.username}\n${credential.password}"

    private fun decode(stored: String): BasicAuthCredential {
        val parts = stored.split('\n', limit = 3)
        if (parts.size != 3 || parts[0] != FORMAT_V1) {
            throw SecureStoreException.CorruptedEntry(
                key = parts.getOrElse(0) { "" },
                message = "Stored Basic Auth credential is missing its format marker",
            )
        }
        return BasicAuthCredential(username = parts[1], password = parts[2])
    }

    public companion object {
        public const val DEFAULT_KEY_PREFIX: String = "basic_auth_credential"
        private const val FORMAT_V1: String = "v1"
    }
}
