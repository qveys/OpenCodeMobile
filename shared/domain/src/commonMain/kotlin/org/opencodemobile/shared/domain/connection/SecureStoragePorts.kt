package org.opencodemobile.shared.domain.connection

/**
 * Port for the per-profile server credential: the bearer token the app attaches
 * to REST/SSE calls once the server identity check has passed (T1,
 * `docs/ARCHITECTURE.md` §"Server identity verification (T1)").
 *
 * Implementations must persist the secret encrypted at rest through the
 * platform secure store (Android Keystore / iOS Keychain) — never in plaintext
 * preferences, a database column, or a file. A plaintext fallback is a B2/T4
 * regression.
 */
public interface ServerCredentialStore {
    /** Returns the stored credential for [profileId], or null when none is saved. */
    public suspend fun credential(profileId: String): ServerCredential?

    /** Persists (or replaces) the credential for [profileId]. */
    public suspend fun storeCredential(profileId: String, credential: ServerCredential)

    /** Deletes the stored credential for [profileId]; absence is not an error. */
    public suspend fun clearCredential(profileId: String)
}

/**
 * A reverse-proxy HTTP Basic Auth credential (username/password), distinct
 * from [ServerCredential]: it authenticates against a Traefik/Dokploy gate
 * sitting in *front of* a self-hosted OpenCode server, not the server's own
 * bearer-token auth. Kept as a small value type for the same reason as
 * [ServerCredential]: it must never be formatted into logs.
 */
public class BasicAuthCredential(
    public val username: String,
    public val password: String,
) {
    init {
        require(username.isNotBlank()) { "BasicAuthCredential.username must not be blank" }
        require(password.isNotBlank()) { "BasicAuthCredential.password must not be blank" }
    }

    /** The password is never rendered; logs and crash reports must not contain it (T4). */
    override fun toString(): String = "BasicAuthCredential(username=$username, password=***)"
}

/**
 * Port for the per-profile reverse-proxy Basic Auth credential. Mirrors
 * [ServerCredentialStore] exactly: same per-profile keying, same encrypted-at-rest
 * requirement, same "absence is not an error" contract.
 */
public interface BasicAuthCredentialStore {
    /** Returns the stored credential for [profileId], or null when none is saved. */
    public suspend fun credential(profileId: String): BasicAuthCredential?

    /** Persists (or replaces) the credential for [profileId]. */
    public suspend fun storeCredential(profileId: String, credential: BasicAuthCredential)

    /** Deletes the stored credential for [profileId]; absence is not an error. */
    public suspend fun clearCredential(profileId: String)
}

/**
 * Port for the stored server record: host, port, optional label, and transport
 * security of the one profile V1 supports (OP3).
 *
 * The record is kept in the same encrypted boundary as the credential so that
 * the profile's trust data stays isolated per [ServerProfile.id] and is
 * excluded from unencrypted OS/cloud backups.
 */
public interface ServerProfileStore {
    /** Returns the stored profile, or null when none has been saved yet. */
    public suspend fun load(): ServerProfile?

    /** Persists [profile], replacing any previously stored record. */
    public suspend fun save(profile: ServerProfile)

    /** Deletes the stored profile; absence is not an error. */
    public suspend fun clear()
}
