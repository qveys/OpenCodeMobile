package org.opencodemobile.shared.domain.connection

/**
 * Outcome of parsing the free-form server address the user types on the
 * manual-entry surface (`docs/ARCHITECTURE.md` §"Server profile import").
 *
 * Parsing is pure and total: it never performs I/O and never throws for user
 * input. Callers branch on [Valid] / [Invalid] instead of on message strings,
 * so the UI can render a specific, localised reason next to the field.
 */
public sealed interface ServerAddressResult {
    /** The input described a well-formed profile. */
    public data class Valid(public val profile: ServerProfile) : ServerAddressResult

    /** The input was not a usable server address; [detail] may carry the offending token. */
    public data class Invalid(
        public val reason: ServerAddressError,
        public val detail: String? = null,
    ) : ServerAddressResult
}

/** Why a manual address was rejected. */
public enum class ServerAddressError {
    /** Nothing (or only whitespace) was entered. */
    Blank,

    /** A scheme other than `http`/`https` was supplied. */
    UnsupportedScheme,

    /** The authority carried user-info (`user:pass@host`); credentials never belong in an address. */
    CredentialsInAddress,

    /** A path other than a single trailing `/` was supplied. */
    PathNotAllowed,

    /** A query string was supplied. */
    QueryNotAllowed,

    /** A fragment was supplied. */
    FragmentNotAllowed,

    /** The host was empty, malformed, or an unbracketed IPv6 literal. */
    InvalidHost,

    /** The port was non-numeric or outside `1..65535`. */
    InvalidPort,
}

/**
 * Parses a manual server address into a [ServerProfile].
 *
 * Accepted forms (case-insensitive scheme, surrounding whitespace ignored):
 * - `host` / `host:port`
 * - `https://host[:port][/]` and `http://host[:port][/]`
 * - bracketed IPv6, e.g. `[fd7a:115c:a1e0::1]:4096`
 *
 * Transport security defaults to HTTPS when no scheme is given: the search for
 * a server must fail safe, and a bare host the user typed is not evidence that
 * they intend plaintext. An explicit `http://` produces a
 * [ServerProfile.TlsMode.PlaintextHttp] profile, which must carry the persistent
 * plaintext warning wherever it is used; refusing plaintext to a public host is
 * a transport-policy decision enforced below this parser (see the connection
 * policy work).
 *
 * The generated [ServerProfile.id] is derived deterministically from the
 * normalised `host:port`, so re-entering or re-importing the same server maps to
 * the same trust/storage key and the "no silent overwrite" comparison in the
 * import review screen is a simple id/authority comparison.
 */
public object ServerAddressParser {
    /** The OpenCode Server default TCP port, used when the input omits one. */
    public const val DEFAULT_PORT: Int = 4096

    private const val MAX_HOST_LENGTH: Int = 253

    /** Parses [input], optionally attaching [label] as the profile's display name. */
    public fun parse(input: String, label: String? = null): ServerAddressResult {
        val raw = input.trim()
        if (raw.isEmpty()) return ServerAddressResult.Invalid(ServerAddressError.Blank)

        if (raw.contains('#')) return ServerAddressResult.Invalid(ServerAddressError.FragmentNotAllowed)
        if (raw.contains('?')) return ServerAddressResult.Invalid(ServerAddressError.QueryNotAllowed)

        val schemeSeparator = raw.indexOf(SCHEME_SEPARATOR)
        val scheme: String
        val remainder: String
        if (schemeSeparator >= 0) {
            scheme = raw.substring(0, schemeSeparator).lowercase()
            remainder = raw.substring(schemeSeparator + SCHEME_SEPARATOR.length)
            if (scheme != "http" && scheme != "https") {
                return ServerAddressResult.Invalid(ServerAddressError.UnsupportedScheme, scheme)
            }
        } else {
            scheme = ""
            remainder = raw
        }

        // Only an empty path or a single trailing '/' is allowed.
        val slash = remainder.indexOf('/')
        val authority = if (slash >= 0) remainder.substring(0, slash) else remainder
        val path = if (slash >= 0) remainder.substring(slash) else ""
        if (path.isNotEmpty() && path != "/") {
            return ServerAddressResult.Invalid(ServerAddressError.PathNotAllowed, path)
        }
        if (authority.isEmpty()) return ServerAddressResult.Invalid(ServerAddressError.InvalidHost)
        if (authority.contains('@')) {
            return ServerAddressResult.Invalid(ServerAddressError.CredentialsInAddress)
        }

        val hostAndPort = splitHostAndPort(authority)
            ?: return ServerAddressResult.Invalid(ServerAddressError.InvalidHost, authority)
        val host = hostAndPort.first
        val port = hostAndPort.second

        if (!isValidHost(host)) return ServerAddressResult.Invalid(ServerAddressError.InvalidHost, host)
        if (port !in 1..65535) return ServerAddressResult.Invalid(ServerAddressError.InvalidPort, port.toString())

        val tls = if (scheme == "http") ServerProfile.TlsMode.PlaintextHttp else ServerProfile.TlsMode.Https
        return ServerAddressResult.Valid(
            ServerProfile(
                id = profileId(host, port),
                host = host,
                port = port,
                label = label?.trim()?.takeIf { it.isNotEmpty() },
                tls = tls,
            ),
        )
    }

    /**
     * The deterministic trust/storage key for a `host:port` pair. Used by both
     * the manual-entry and import paths so the same server is always the same
     * profile.
     */
    public fun profileId(host: String, port: Int): String =
        "server:${normaliseHost(host)}:$port"

    /** Lowercases and strips the IPv6 brackets used in address literals. */
    public fun normaliseHost(host: String): String =
        host.trim().lowercase().removePrefix("[").removeSuffix("]")

    /**
     * Splits `authority` into host and port. Returns null when the authority is
     * ambiguous (e.g. an unbracketed IPv6 literal) or the port is not an
     * integer. A missing port yields [DEFAULT_PORT].
     */
    private fun splitHostAndPort(authority: String): Pair<String, Int>? {
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return null
            val host = authority.substring(1, close)
            val rest = authority.substring(close + 1)
            return when {
                rest.isEmpty() -> host to DEFAULT_PORT
                rest.startsWith(":") -> {
                    val port = rest.substring(1).toIntOrNull() ?: return null
                    host to port
                }
                else -> null
            }
        }

        val firstColon = authority.indexOf(':')
        if (firstColon < 0) return authority to DEFAULT_PORT
        // A second ':' means an unbracketed IPv6 literal, which is ambiguous.
        if (authority.indexOf(':', firstColon + 1) >= 0) return null
        val host = authority.substring(0, firstColon)
        val port = authority.substring(firstColon + 1).toIntOrNull() ?: return null
        return host to port
    }

    private fun isValidHost(host: String): Boolean {
        if (host.isEmpty() || host.length > MAX_HOST_LENGTH) return false
        return host.all { character ->
            when (character) {
                in 'a'..'z', in 'A'..'Z', in '0'..'9' -> true
                '.', '-', '_', ':' -> true
                else -> false
            }
        }
    }

    private const val SCHEME_SEPARATOR: String = "://"
}
