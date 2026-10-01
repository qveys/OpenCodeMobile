package org.opencodemobile.shared.domain.connection

/**
 * Where a server profile's host sits on the network path
 * (`docs/ARCHITECTURE.md` §"Connection policy").
 *
 * The app is a remote control for a self-hosted server reached over a private
 * LAN or a Tailscale tailnet. Plaintext HTTP is tolerable there (the network is
 * already the trust boundary, and TOFU still applies), but off that private
 * path TLS is mandatory.
 */
public enum class ServerNetworkScope {
    /** The device itself (`localhost`, `127.0.0.0/8`, `::1`). */
    Loopback,

    /** A private LAN address (RFC 1918, link-local) or an mDNS `.local` name. */
    Lan,

    /** A Tailscale tailnet address (CGNAT `100.64.0.0/10`, `*.ts.net`, Tailscale ULA). */
    Tailscale,

    /** Any other host; TLS is mandatory here. */
    Public,
}

/** Reason a profile was rejected by [HttpConnectionPolicy]. */
public enum class HttpPolicyViolation {
    /**
     * Plaintext HTTP to a public host. Without TLS the credential and all
     * traffic are readable to anyone on the network path, so the connection is
     * refused before any request is built.
     */
    PublicPlaintextHttp,
}

/** Result of evaluating a [ServerProfile] against [HttpConnectionPolicy]. */
public sealed interface HttpConnectionPolicyDecision {
    /** The profile may be used. [plaintext] is true for a warning-bearing HTTP profile. */
    public data class Allowed(
        public val scope: ServerNetworkScope,
        public val plaintext: Boolean,
    ) : HttpConnectionPolicyDecision

    /** The profile must not be used; no request may be sent. */
    public data class Rejected(
        public val scope: ServerNetworkScope,
        public val violation: HttpPolicyViolation,
    ) : HttpConnectionPolicyDecision
}

/**
 * The connection policy: which transports are allowed, where.
 * (`docs/ARCHITECTURE.md` §"Server identity verification (T1)" left the
 * plaintext-HTTP address restriction to the L1 connection design; this is that
 * decision.)
 *
 * Rules:
 * - HTTPS is allowed to any host, including public ones.
 * - Plaintext HTTP is allowed only on [ServerNetworkScope.Loopback],
 *   [ServerNetworkScope.Lan], and [ServerNetworkScope.Tailscale]. Those
 *   profiles still carry the persistent plaintext warning from
 *   `PlaintextHttpWarning` (T1).
 * - Plaintext HTTP to [ServerNetworkScope.Public] is [Rejected]: TLS is
 *   required for any external connection.
 *
 * Host classification is a client-side guard, not a substitute for the
 * identity check: [ServerProfile] values are user-supplied, so the policy is a
 * defense-in-depth control on top of TOFU pinning.
 */
public object HttpConnectionPolicy {

    /**
     * The HTTP methods this client is allowed to send to an OpenCode Server.
     *
     * The app is outbound-only and the generated client uses exactly these
     * verbs (read with `GET`, act with `POST`, remove with `DELETE`). Any other
     * method is a policy violation and is refused before it reaches the
     * network, which keeps the outbound surface minimal if a future change
     * starts building requests by hand.
     */
    public val allowedMethods: Set<String> = setOf("GET", "POST", "DELETE")

    /** Whether [method] (case-insensitive, trimmed) is in [allowedMethods]. */
    public fun isMethodAllowed(method: String): Boolean =
        method.trim().uppercase() in allowedMethods

    /** Evaluates [profile] without performing any I/O. */
    public fun decide(profile: ServerProfile): HttpConnectionPolicyDecision {
        val scope = scopeOf(profile.host)
        return if (profile.isPlaintextHttp && scope == ServerNetworkScope.Public) {
            HttpConnectionPolicyDecision.Rejected(
                scope = scope,
                violation = HttpPolicyViolation.PublicPlaintextHttp,
            )
        } else {
            HttpConnectionPolicyDecision.Allowed(
                scope = scope,
                plaintext = profile.isPlaintextHttp,
            )
        }
    }

    /**
     * Classifies a bare host (no scheme, port, or path). IP literals are
     * matched against the private, CGNAT, and Tailscale ranges; hostnames are
     * matched against mDNS and Tailscale MagicDNS suffixes. Anything else is
     * [ServerNetworkScope.Public].
     */
    public fun scopeOf(host: String): ServerNetworkScope {
        val normalised = host.trim().lowercase().removeSurrounding("[", "]")
        if (normalised.isEmpty()) return ServerNetworkScope.Public

        parseIpv4(normalised)?.let { return scopeOfIpv4(it) }
        if (normalised.contains(':')) return scopeOfIpv6(normalised)

        return when {
            normalised == "localhost" || normalised.endsWith(".localhost") ->
                ServerNetworkScope.Loopback

            normalised.endsWith(".local") -> ServerNetworkScope.Lan
            normalised.endsWith(".ts.net") -> ServerNetworkScope.Tailscale
            else -> ServerNetworkScope.Public
        }
    }

    private fun scopeOfIpv4(octets: IntArray): ServerNetworkScope {
        val first = octets[0]
        val second = octets[1]
        return when {
            first == 127 -> ServerNetworkScope.Loopback
            first == 10 -> ServerNetworkScope.Lan
            first == 172 && second in 16..31 -> ServerNetworkScope.Lan
            first == 192 && second == 168 -> ServerNetworkScope.Lan
            first == 169 && second == 254 -> ServerNetworkScope.Lan
            first == 100 && second in 64..127 -> ServerNetworkScope.Tailscale
            else -> ServerNetworkScope.Public
        }
    }

    private fun scopeOfIpv6(host: String): ServerNetworkScope {
        if (host == "::1" || host == "0:0:0:0:0:0:0:1") return ServerNetworkScope.Loopback
        // Tailscale assigns from fd7a:115c:a1e0::/48 inside the ULA range.
        if (host.startsWith("fd7a:115c:a1e0")) return ServerNetworkScope.Tailscale

        val firstGroup = host.substringBefore(':').toIntOrNull(16) ?: return ServerNetworkScope.Public
        return when {
            // fc00::/7 (unique local addresses)
            (firstGroup and 0xfe00) == 0xfc00 -> ServerNetworkScope.Lan
            // fe80::/10 (link-local)
            (firstGroup and 0xffc0) == 0xfe80 -> ServerNetworkScope.Lan
            else -> ServerNetworkScope.Public
        }
    }

    /** Returns the four octets of a dotted-quad IPv4 literal, or null. */
    private fun parseIpv4(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null

        val octets = IntArray(4)
        for (index in 0 until 4) {
            val part = parts[index]
            if (part.isEmpty() || part.length > 3 || !part.all { it.isDigit() }) return null
            val value = part.toInt()
            if (value > 255) return null
            octets[index] = value
        }
        return octets
    }
}
