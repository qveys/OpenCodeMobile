package org.opencodemobile.shared.domain.connection

/**
 * The set of OpenCode Server versions the app can talk to, used as the version
 * gate of the connection handshake (`docs/ARCHITECTURE.md` §4.4).
 *
 * The profile is intentionally narrow: it pins one server major line and a
 * minimum version. A newer version in the same major line is treated as
 * forward-compatible (an additive minor/patch release); a different major line
 * or an older-than-minimum version is [CompatibilityResult.Incompatible].
 *
 * The full compatibility matrix is an L6 deliverable; this profile is the L1
 * gate for the pinned server (`docs/ARCHITECTURE.md` §4.2).
 */
public data class CompatibilityProfile(
    /** The server major version this client was generated against (`1` for the pinned spec). */
    public val majorVersion: Int,
    /** The oldest supported version. Any version `>= minimumVersion` in [majorVersion] is accepted. */
    public val minimumVersion: ServerVersion,
) {
    init {
        require(majorVersion >= 0) { "CompatibilityProfile.majorVersion must not be negative: $majorVersion" }
        require(minimumVersion.major == majorVersion) {
            "CompatibilityProfile.minimumVersion (${minimumVersion}) must be in major line $majorVersion"
        }
    }

    /**
     * Evaluates a server-reported version. A null [version] (missing or
     * unparseable) is [CompatibilityResult.Unknown]; callers treat it as an
     * incomplete handshake, not as compatible.
     */
    public fun evaluate(version: ServerVersion?): CompatibilityResult = when {
        version == null -> CompatibilityResult.Unknown
        version.major != majorVersion -> CompatibilityResult.Incompatible(version, this)
        version < minimumVersion -> CompatibilityResult.Incompatible(version, this)
        else -> CompatibilityResult.Compatible(version)
    }

    /** Human-readable supported range, for the incompatible-server screen. */
    public val supportedRange: String
        get() = "$majorVersion.x (>= $minimumVersion)"

    public companion object {
        /**
         * The compatibility profile for the pinned OpenCode Server v2
         * (`docs/ARCHITECTURE.md` §4.2, spec `1.18.32`): server major line `1`,
         * not older than `1.18.0`.
         */
        public val OpenCodeServerV2: CompatibilityProfile = CompatibilityProfile(
            majorVersion = 1,
            minimumVersion = ServerVersion(1, 18, 0),
        )
    }
}

/** Outcome of checking a server version against a [CompatibilityProfile]. */
public sealed interface CompatibilityResult {
    /** The server version is inside the supported range. */
    public data class Compatible(public val version: ServerVersion) : CompatibilityResult

    /** The server version is outside the supported range. */
    public data class Incompatible(
        public val serverVersion: ServerVersion,
        public val profile: CompatibilityProfile,
    ) : CompatibilityResult

    /** No parseable version was reported, so compatibility cannot be established. */
    public data object Unknown : CompatibilityResult
}
