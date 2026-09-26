package org.opencodemobile.shared.domain.connection

/**
 * The app's stored record of one self-hosted OpenCode Server instance.
 *
 * A profile is the unit of trust: the TOFU-pinned server identity
 * (`docs/ARCHITECTURE.md` §"Server identity verification (T1)") and the server
 * credential are both keyed by [id], so trust is isolated per profile.
 *
 * This type lives in `shared/domain` and therefore carries no platform, Ktor,
 * or persistence dependency; secure storage of the credential and of the pinned
 * fingerprint is behind `shared/security` ports.
 */
public data class ServerProfile(
    /** Stable identifier used as the trust/storage key for this profile. */
    public val id: String,
    /** Bare host name or IP literal, without scheme, port, or path. */
    public val host: String,
    /** TCP port of the OpenCode Server. */
    public val port: Int,
    /** Optional human-readable label shown in the UI. */
    public val label: String? = null,
    /** Transport security of this profile. */
    public val tls: TlsMode = TlsMode.Https,
) {
    init {
        require(id.isNotBlank()) { "ServerProfile.id must not be blank" }
        require(host.isNotBlank()) { "ServerProfile.host must not be blank" }
        require(port in 1..65535) { "ServerProfile.port out of range: $port" }
    }

    /** Whether this profile has no TLS at all (see the plaintext-HTTP warning). */
    public val isPlaintextHttp: Boolean
        get() = tls == TlsMode.PlaintextHttp

    /** `host:port` authority, as displayed to the user during review. */
    public val authority: String
        get() = "$host:$port"

    /** Base URL for REST/SSE calls. */
    public val baseUrl: String
        get() = "${if (isPlaintextHttp) "http" else "https"}://$authority"

    /** Transport security mode. */
    public enum class TlsMode {
        /** HTTPS; a leaf certificate exists and its SPKI can be pinned (T1). */
        Https,

        /**
         * Plaintext HTTP. There is no certificate to pin, so server identity
         * cannot be verified and the credential is readable on the network
         * path. Allowed only with a persistent textual warning (T1).
         */
        PlaintextHttp,
    }
}