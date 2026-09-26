package org.opencodemobile.shared.domain.connection

/**
 * Port for the per-profile TOFU fingerprint pin: the Keychain/Keystore-backed
 * record of the SPKI fingerprint the user has accepted for a server profile
 * (B2; `docs/ARCHITECTURE.md` §"Server identity verification (T1)").
 *
 * Implementations must persist through the platform secure store (Android
 * Keystore / iOS Keychain); an implementation that silently downgrades to
 * plaintext storage is a T1 regression.
 */
public interface ServerIdentityStore {
    /** Returns the pinned fingerprint for [profileId], or null on first contact. */
    public suspend fun pinnedFingerprint(profileId: String): ServerFingerprint?

    /** Persists [fingerprint] as the profile's pinned identity. */
    public suspend fun storePinnedFingerprint(profileId: String, fingerprint: ServerFingerprint)

    /** Forgets the pinned identity (used when the user resets/removes a profile). */
    public suspend fun clearPinnedFingerprint(profileId: String)
}

/**
 * Port for reading the leaf-certificate SPKI fingerprint actually presented by
 * the server during a TLS handshake.
 *
 * The implementation is platform-specific because it must observe the real
 * handshake (OkHttp `X509TrustManager` on Android, `URLSession` challenge
 * delegate on iOS). It must never return a fingerprint that was not observed
 * on the wire.
 */
public interface ServerIdentityVerifier {
    /**
     * Opens a probe connection to [profile] and returns the SHA-256 SPKI of the
     * leaf certificate the server presented.
     *
     * @throws ServerIdentityException.NoCertificatePresented when the handshake
     *   yields no leaf certificate.
     */
    public suspend fun presentedFingerprint(profile: ServerProfile): ServerFingerprint
}