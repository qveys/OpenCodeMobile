package org.opencodemobile.shared.security.store

import org.opencodemobile.shared.domain.connection.ServerCredential
import org.opencodemobile.shared.domain.connection.ServerCredentialStore

/**
 * [ServerCredentialStore] backed by an encrypted [SecureStore].
 *
 * The credential is stored under a per-profile key so trust stays isolated
 * between profiles (T8/T1), and is wrapped in a small versioned envelope so
 * future credential shapes can be added without misreading existing entries.
 * This is the integration point with the OpenCode Server v2 adapter:
 * `OpenCodeGateway.connect(profile, credentialStore.credential(profile.id))`.
 */
public class SecureServerCredentialStore(
    private val store: SecureStore,
    private val keyPrefix: String = DEFAULT_KEY_PREFIX,
) : ServerCredentialStore {

    override suspend fun credential(profileId: String): ServerCredential? {
        val stored = store.get(entryKey(profileId)) ?: return null
        return decode(stored)
    }

    override suspend fun storeCredential(profileId: String, credential: ServerCredential) {
        store.put(entryKey(profileId), encode(credential))
    }

    override suspend fun clearCredential(profileId: String) {
        store.remove(entryKey(profileId))
    }

    private fun entryKey(profileId: String): String {
        require(profileId.isNotBlank()) { "A server credential must be keyed by a non-blank profile id" }
        return "$keyPrefix:$profileId"
    }

    private fun encode(credential: ServerCredential): String = "$FORMAT_V1\n${credential.bearerToken}"

    private fun decode(stored: String): ServerCredential {
        val newline = stored.indexOf('\n')
        if (newline <= 0) {
            throw SecureStoreException.CorruptedEntry(
                key = FORMAT_V1,
                message = "Stored server credential is missing its format marker",
            )
        }
        val format = stored.substring(0, newline)
        if (format != FORMAT_V1) {
            throw SecureStoreException.CorruptedEntry(
                key = format,
                message = "Unsupported stored server credential format '$format'",
            )
        }
        return ServerCredential(stored.substring(newline + 1))
    }

    public companion object {
        public const val DEFAULT_KEY_PREFIX: String = "server_credential"
        private const val FORMAT_V1: String = "v1"
    }
}
