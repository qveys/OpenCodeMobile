package org.opencodemobile.shared.domain.cache

/**
 * Read models for the disposable local cache (D8; `docs/ARCHITECTURE.md` §3.4).
 *
 * The cache is scoped `ServerId -> ProjectId -> SessionId` and is **never a
 * source of truth**: it can be wiped and rebuilt from the next server snapshot.
 * These types deliberately carry **no** credential, token, or pin field — the
 * cache must never hold a secret (§7.3; OPE-107 acceptance).
 *
 * They live in `shared/domain` and therefore carry no platform, SQLDelight, or
 * serialization dependency.
 */

/** Cached server configuration, keyed by `ServerId`. Never holds the credential. */
public data class CachedServerConfig(
    public val serverId: String,
    public val host: String,
    public val port: Int,
    public val tlsMode: String,
    public val label: String? = null,
)

/** Cached project metadata, scoped to a server. */
public data class CachedProject(
    public val serverId: String,
    public val projectId: String,
    public val name: String,
    public val path: String? = null,
    public val updatedAt: Long = 0L,
)

/** Cached session metadata, scoped `ServerId -> ProjectId`. */
public data class CachedSession(
    public val serverId: String,
    public val projectId: String,
    public val sessionId: String,
    public val title: String? = null,
    public val parentSessionId: String? = null,
    public val createdAt: Long = 0L,
    public val updatedAt: Long = 0L,
)

/** One cached transcript message. [sequence] orders messages within a session. */
public data class CachedTranscriptMessage(
    public val serverId: String,
    public val projectId: String,
    public val sessionId: String,
    public val sequence: Long,
    public val role: String,
    public val content: String,
    public val createdAt: Long = 0L,
)

/** The cached composer draft for a session. */
public data class CachedDraft(
    public val serverId: String,
    public val projectId: String,
    public val sessionId: String,
    public val body: String,
    public val updatedAt: Long = 0L,
)

/** A non-secret application preference (never used to store key material). */
public data class CachedPreference(
    public val key: String,
    public val value: String,
)

/**
 * Sync bookkeeping for a scope. [sessionId] is the empty string for a
 * project-level cursor.
 */
public data class CachedSyncMetadata(
    public val serverId: String,
    public val projectId: String,
    public val sessionId: String,
    public val lastEventId: String? = null,
    public val snapshotVersion: Long = 0L,
    public val lastSyncedAt: Long = 0L,
)
