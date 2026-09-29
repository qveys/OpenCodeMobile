package org.opencodemobile.shared.domain.cache

/**
 * Read port over the disposable local cache (D8; `docs/ARCHITECTURE.md` §3.4).
 *
 * This is the surface the app uses when it has no live connection: it renders
 * previously-cached data in **read-only** mode. Every method is scoped
 * `ServerId -> ProjectId -> SessionId`, so keeping `ServerId` in the Domain
 * (OP3) makes multi-profile a data-layer change, not a rewrite.
 *
 * The cache is never a source of truth and every read is expected to be a
 * best-effort fallback for a live server.
 */
public interface SessionCache {
    /** Cached server configuration for [serverId], if present. */
    public suspend fun serverConfig(serverId: String): CachedServerConfig?

    /** Cached projects for [serverId], newest activity first. */
    public suspend fun projects(serverId: String): List<CachedProject>

    /** Cached sessions in [projectId], most recently updated first. */
    public suspend fun sessions(serverId: String, projectId: String): List<CachedSession>

    /** A single cached session, or null when the scope has never been seen. */
    public suspend fun session(serverId: String, projectId: String, sessionId: String): CachedSession?

    /** The most recent [limit] transcript messages of a session, in order. */
    public suspend fun recentTranscript(
        serverId: String,
        projectId: String,
        sessionId: String,
        limit: Int,
    ): List<CachedTranscriptMessage>

    /** The cached draft for a session, if any. */
    public suspend fun draft(serverId: String, projectId: String, sessionId: String): CachedDraft?

    /** A non-secret preference value for [serverId], if present. */
    public suspend fun preference(serverId: String, key: String): String?

    /** Sync bookkeeping for a scope (empty [CachedSyncMetadata.sessionId] = project-level). */
    public suspend fun syncMetadata(
        serverId: String,
        projectId: String,
        sessionId: String,
    ): CachedSyncMetadata?
}

/**
 * Write port for the cache.
 *
 * The cache is written **only** from server events and snapshots — never from a
 * user action and never while offline (D8). Implementations are therefore
 * application-internal: the event/snapshot pipeline is the only caller, which
 * is what keeps the offline surface read-only.
 */
public interface SessionCacheWriter {
    public suspend fun putServerConfig(config: CachedServerConfig)

    public suspend fun putProject(project: CachedProject)

    public suspend fun putSession(session: CachedSession)

    /** Upserts a transcript message and prunes the session to the last [keepLast]. */
    public suspend fun putTranscriptMessage(message: CachedTranscriptMessage, keepLast: Int)

    public suspend fun putDraft(draft: CachedDraft)

    public suspend fun putPreference(preference: CachedPreference)

    public suspend fun putSyncMetadata(metadata: CachedSyncMetadata)

    /** Drops everything cached for [serverId] (used when a profile is removed). */
    public suspend fun wipeServer(serverId: String)
}

/**
 * Port for the cache's at-rest encryption key (B2; T3 / OP2).
 *
 * Android generates a random passphrase, wraps it with a non-exportable
 * Keystore key (AES/GCM), and stores only the ciphertext — the passphrase is
 * never persisted in cleartext. A missing or invalidated key (for example after
 * a biometric re-enrollment) is a **cache miss**, not a fatal error: callers wipe
 * and rebuild from the next snapshot (`docs/ARCHITECTURE.md` §"Local cache
 * encryption at rest").
 *
 * Implementations must never store the passphrase in cleartext, and must not
 * treat a plain preferences file as the source of truth for key material. iOS
 * relies on OS Data Protection instead and does not use this port.
 */
public interface CacheKeyStore {
    /** Returns the stored passphrase, or null when absent or invalidated. */
    public suspend fun existingPassphrase(): ByteArray?

    /** Generates, stores, and returns a fresh random passphrase. */
    public suspend fun createPassphrase(): ByteArray

    /** Deletes the stored passphrase; the next open generates a new one. */
    public suspend fun deletePassphrase()
}

/** Connectivity as the UI policy sees it. */
public enum class ConnectionState {
    Online,

    /** Offline is read-only: no mutation is offered or queued (D8). */
    Offline,
}

/**
 * Single gate every mutation surface must consult before offering or performing
 * a mutation. Offline, [mutationsAllowed] is false, so write/edit/delete/send
 * affordances stay disabled and nothing is queued (D8).
 */
public interface MutationGate {
    public fun mutationsAllowed(): Boolean
}

/**
 * Value-level guard for the cache's key/value preference table.
 *
 * The schema has no secret column, but a generic KV table would still accept a
 * token under an arbitrary key. Every [SessionCacheWriter.putPreference] must
 * pass [requireNonSecretKey]; the cache never holds a secret (§7.3).
 */
public object CachePreferencePolicy {
    private val SECRET_BEARING_KEY = Regex(
        "(?i)(token|secret|password|passphrase|credential|apikey|api_key|authorization|bearer|auth|pin|private_?key)",
    )

    /** Whether [key] looks like it could name a credential. */
    public fun isSecretBearingKey(key: String): Boolean = SECRET_BEARING_KEY.containsMatchIn(key)

    /** Throws [IllegalArgumentException] when [key] looks secret-bearing. */
    public fun requireNonSecretKey(key: String) {
        require(!isSecretBearingKey(key)) {
            "Refusing to cache a preference whose key looks like a secret " +
                "(no secrets in the cache, §7.3): $key"
        }
    }
}

/** Thrown when a mutation is attempted while the cache is offline read-only (D8). */
public class CacheMutationNotAllowedException(
    message: String = "Mutations are disabled while offline (D8); the cache is read-only",
) : IllegalStateException(message)

/**
 * Thrown when the cache cannot be opened even after a wipe-and-rebuild attempt.
 *
 * This is a platform/storage failure, not key loss: the application should
 * render an empty, read-only state rather than crash.
 */
public class CacheUnavailableException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
