package org.opencodemobile.shared.security.store

import org.opencodemobile.shared.domain.connection.ServerProfile
import org.opencodemobile.shared.domain.connection.ServerProfileStore

/**
 * [ServerProfileStore] backed by an encrypted [SecureStore] (OP3: one profile).
 *
 * Host, port, label, and transport security are not credentials, but they are
 * the profile's trust descriptor: keeping them inside the same encrypted,
 * backup-excluded boundary as the credential and the identity pin means the
 * whole trust unit moves together and cannot be partially restored.
 *
 * The record uses a small versioned, escaped line format so no serialization
 * dependency is needed in `shared/security` (architecture rule §5.2.7).
 */
public class SecureServerProfileStore(
    private val store: SecureStore,
    private val entryKey: String = DEFAULT_ENTRY_KEY,
) : ServerProfileStore {

    override suspend fun load(): ServerProfile? =
        translate { store.get(entryKey)?.let(::decode) }

    override suspend fun save(profile: ServerProfile) {
        translate { store.put(entryKey, encode(profile)) }
    }

    override suspend fun clear() {
        translate { store.remove(entryKey) }
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

    private fun encode(profile: ServerProfile): String = buildString {
        append(FORMAT_V1).append('\n')
        append(FIELD_ID).append('=').append(escape(profile.id)).append('\n')
        append(FIELD_HOST).append('=').append(escape(profile.host)).append('\n')
        append(FIELD_PORT).append('=').append(profile.port).append('\n')
        append(FIELD_TLS).append('=').append(profile.tls.name).append('\n')
        append(FIELD_LABEL).append('=').append(profile.label?.let(::escape).orEmpty())
    }

    private fun decode(stored: String): ServerProfile {
        val lines = stored.split('\n')
        val format = lines.firstOrNull()
        if (format != FORMAT_V1) {
            throw SecureStoreException.CorruptedEntry(
                key = entryKey,
                message = "Unsupported stored server profile format '$format'",
            )
        }
        val fields = HashMap<String, String>()
        for (line in lines.drop(1)) {
            if (line.isEmpty()) continue
            val separator = line.indexOf('=')
            if (separator <= 0) {
                throw SecureStoreException.CorruptedEntry(
                    key = entryKey,
                    message = "Malformed stored server profile field '$line'",
                )
            }
            fields[line.substring(0, separator)] = unescape(line.substring(separator + 1))
        }

        val id = fields[FIELD_ID].orEmpty()
        val host = fields[FIELD_HOST].orEmpty()
        val port = fields[FIELD_PORT]?.toIntOrNull()
            ?: throw SecureStoreException.CorruptedEntry(entryKey, "Stored server profile has no valid port")
        val tls = fields[FIELD_TLS]?.let { name ->
            ServerProfile.TlsMode.entries.firstOrNull { it.name == name }
        } ?: throw SecureStoreException.CorruptedEntry(entryKey, "Stored server profile has no valid TLS mode")
        val label = fields[FIELD_LABEL]?.takeIf { it.isNotEmpty() }

        return try {
            ServerProfile(id = id, host = host, port = port, label = label, tls = tls)
        } catch (failure: IllegalArgumentException) {
            throw SecureStoreException.CorruptedEntry(entryKey, "Stored server profile is invalid", failure)
        }
    }

    /** Escapes the three characters that would break the line-based envelope. */
    private fun escape(value: String): String = buildString(value.length) {
        for (character in value) {
            when (character) {
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(character)
            }
        }
    }

    private fun unescape(value: String): String = buildString(value.length) {
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (character == '\\' && index + 1 < value.length) {
                when (value[index + 1]) {
                    '\\' -> { append('\\'); index += 2 }
                    'n' -> { append('\n'); index += 2 }
                    'r' -> { append('\r'); index += 2 }
                    else -> { append(character); index += 1 }
                }
            } else {
                append(character)
                index += 1
            }
        }
    }

    public companion object {
        public const val DEFAULT_ENTRY_KEY: String = "server_profile"

        private const val FORMAT_V1: String = "v1"
        private const val FIELD_ID: String = "id"
        private const val FIELD_HOST: String = "host"
        private const val FIELD_PORT: String = "port"
        private const val FIELD_TLS: String = "tls"
        private const val FIELD_LABEL: String = "label"
    }
}
