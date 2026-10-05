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
