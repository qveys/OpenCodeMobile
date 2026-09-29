package org.opencodemobile.shared.security.store

/**
 * Description of the dedicated symmetric key that protects one [SecureStore].
 *
 * Each store owns its own key, generated and held by the platform keystore
 * (Android Keystore / iOS Keychain); the key material never leaves it. The
 * descriptor exists for diagnostics and settings surfaces — it never carries
 * key material.
 */
public data class SecureKeyDescriptor(
    /** Platform keystore identifier (Android alias / iOS Keychain service). */
    public val alias: String,
    /** Human-readable algorithm, e.g. `AES-256-GCM`. */
    public val algorithm: String,
    /** Whether the key is held by hardware-backed storage. */
    public val hardwareBacked: Boolean,
)

/**
 * Encrypted key/value storage for secrets (server credentials, cache
 * passphrases, short-lived pairing codes) and for the server record itself.
 *
 * Values are encrypted at rest with a dedicated platform key and persisted as
 * ciphertext; callers never see the key or the on-disk form. Implementations
 * must fail closed: a missing or unusable key never downgrades to plaintext
 * storage. Keys are namespaced by the caller so sibling stores stay isolated.
 *
 * Secrets returned by [get] must never be logged or formatted into strings.
 */
public interface SecureStore {
    /** Describes the dedicated key that encrypts this store. */
    public val keyDescriptor: SecureKeyDescriptor

    /** Returns the plaintext stored for [key], or null when absent. */
    public suspend fun get(key: String): String?

    /** Encrypts [value] and stores it under [key], replacing any previous value. */
    public suspend fun put(key: String, value: String)

    /** Deletes the entry [key]; absence is not an error. */
    public suspend fun remove(key: String)

    /** Deletes every entry and the dedicated key that protected them. */
    public suspend fun destroy()
}

/**
 * Typed failures raised by a [SecureStore]. Callers branch on these instead of
 * on message strings; none of them authorizes a plaintext fallback.
 */
public sealed class SecureStoreException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause) {

    /** The dedicated key could not be created or read. */
    public class KeyUnavailable(message: String, cause: Throwable? = null) :
        SecureStoreException(message, cause)

    /** An entry exists but cannot be decrypted or parsed (tampered or foreign data). */
    public class CorruptedEntry(public val key: String, message: String, cause: Throwable? = null) :
        SecureStoreException(message, cause)

    /** The ciphertext could not be written. */
    public class WriteFailed(message: String, cause: Throwable? = null) :
        SecureStoreException(message, cause)
}
