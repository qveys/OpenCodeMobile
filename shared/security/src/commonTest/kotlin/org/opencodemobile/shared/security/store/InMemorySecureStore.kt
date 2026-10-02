package org.opencodemobile.shared.security.store

/**
 * In-memory [SecureStore] for tests. It performs no encryption: the platform
 * stores own the encryption-at-rest guarantees and are exercised by their own
 * device tests. Here we only verify the key layout and codecs layered on top.
 */
internal class InMemorySecureStore(
    override val keyDescriptor: SecureKeyDescriptor = SecureKeyDescriptor(
        alias = "test",
        algorithm = "none",
        hardwareBacked = false,
    ),
) : SecureStore {

    val entries: MutableMap<String, String> = mutableMapOf()

    override suspend fun get(key: String): String? = entries[key]

    override suspend fun put(key: String, value: String) {
        entries[key] = value
    }

    override suspend fun remove(key: String) {
        entries.remove(key)
    }

    override suspend fun destroy() {
        entries.clear()
    }
}
