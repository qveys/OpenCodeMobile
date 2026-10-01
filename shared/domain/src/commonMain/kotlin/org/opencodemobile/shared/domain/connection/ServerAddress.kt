package org.opencodemobile.shared.domain.connection

/**
 * A validated manual server entry: the address the user typed, normalised into
 * the host/port/transport triple the rest of the app uses
 * (`docs/ARCHITECTURE.md` §"Server profile import").
 *
 * This is the *input* form: it is not yet persisted. The connection feature
 * shows it on the review screen and only then turns it into a [ServerProfile].
 * Hosts are stored without IPv6 brackets; [authority] re-adds them for display
 * and for building a URL.
 */
public data class ServerAddress(
    /** Bare host name or IP literal, without scheme, port, brackets, or path. */
    public val host: String,
    /** TCP port; always set (the parser defaults to [ServerAddressParser.DEFAULT_PORT]). */
    public val port: Int,
    /** Transport security, derived from the typed scheme (defaults to HTTPS). */
    public val tls: ServerProfile.TlsMode = ServerProfile.TlsMode.Https,
    /** Optional human-readable label; not parsed from the address itself. */
    public val label: String? = null,
) {
    init {
        require(host.isNotBlank()) { "ServerAddress.host must not be blank" }
        require(port in 1..65535) { "ServerAddress.port out of range: $port" }
    }

    /** `host:port`, with IPv6 hosts bracketed, as displayed on the review screen. */
    public val authority: String
        get() = if (host.contains(':')) "[$host]:$port" else "$host:$port"

    /**
     * Deterministic profile id for this address. Two entries that normalise to
     * the same host and port share an id, which is what lets the import flow
     * detect "this already exists" and switch to the update view instead of
     * silently overwriting (`docs/ARCHITECTURE.md` §"No silent overwrite").
     */
    public fun profileId(): String = "${host.lowercase()}:$port"

    /** Builds the storable profile; [id] defaults to [profileId]. */
    public fun toProfile(id: String = profileId()): ServerProfile = ServerProfile(
        id = id,
        host = host,
        port = port,
        label = label,
        tls = tls,
    )
}

/** Outcome of parsing a manual server-address entry. */
public sealed interface ServerAddressResult {
    /** The input was a valid server address. */
    public data class Valid(public val address: ServerAddress) : ServerAddressResult

    /** The input could not be used; [error] explains what to fix. */
    public data class Invalid(public val error: DomainError.InvalidServerAddress) : ServerAddressResult
}

/**
 * Parses manual server-address input (`docs/ARCHITECTURE.md` §"Manual entry").
 *
 * Accepted forms:
 * - `host` and `host:port`
 * - `http://host[:port]`, `https://host[:port]`
 * - bracketed IPv6: `[fd7a:115c:a1e0::1]:4096`
 * - optional trailing `/`
 *
 * Rules:
 * - The scheme is optional. With no scheme the parser defaults to **HTTPS**
 *   (the safe default; HTTPS is policy-allowed to every host). A plaintext
 *   server must be typed as `http://…` and then carries the plaintext warning.
 * - A path, query, or fragment is rejected; only the host and port are used.
 * - User-info (`user:pass@`) is rejected: credentials never go in an address
 *   (T8).
 * - The port defaults to [DEFAULT_PORT] when omitted.
 */
public object ServerAddressParser {

    /** The OpenCode Server conventional port, used when the input omits one. */
    public const val DEFAULT_PORT: Int = 4096

    /** Parses [raw], returning either a normalised address or a typed error. */
    public fun parse(raw: String): ServerAddressResult {
        val input = raw.trim()
        if (input.isEmpty()) {
            return invalid(ServerInputProblem.BLANK, raw, ServerAddressField.HOST)
        }

        val scheme = parseScheme(input)
            ?: return invalid(ServerInputProblem.UNKNOWN_SCHEME, raw, ServerAddressField.SCHEME)

        val authority = when (val extracted = extractAuthority(scheme.rest)) {
            is AuthorityResult.Ok -> extracted.authority
            is AuthorityResult.Failed -> return invalid(extracted.problem, raw, extracted.field)
        }

        val split = splitAuthority(authority)
            ?: return invalid(ServerInputProblem.MALFORMED, raw, ServerAddressField.HOST)

        ServerHostGrammar.problem(split.host)?.let { problem ->
            return invalid(problem, raw, ServerAddressField.HOST)
        }

        val port = when (val portText = split.portText) {
            null -> DEFAULT_PORT
            "" -> return invalid(ServerInputProblem.INVALID_PORT, raw, ServerAddressField.PORT)
            else -> {
                if (!portText.all { it.isDigit() }) {
                    return invalid(ServerInputProblem.INVALID_PORT, raw, ServerAddressField.PORT)
                }
                val value = portText.toIntOrNull()
                    ?: return invalid(ServerInputProblem.INVALID_PORT, raw, ServerAddressField.PORT)
                if (value !in 1..65535) {
                    return invalid(ServerInputProblem.PORT_OUT_OF_RANGE, raw, ServerAddressField.PORT)
                }
                value
            }
        }

        return ServerAddressResult.Valid(ServerAddress(host = split.host, port = port, tls = scheme.tls))
    }

    /** Convenience: the parsed address, or null when [raw] is invalid. */
    public fun parseOrNull(raw: String): ServerAddress? =
        (parse(raw) as? ServerAddressResult.Valid)?.address

    private data class ParsedScheme(val tls: ServerProfile.TlsMode, val rest: String)

    /** Splits the optional `scheme://` prefix; null only for an unknown scheme. */
    private fun parseScheme(input: String): ParsedScheme? {
        val separator = input.indexOf("://")
        if (separator < 0) return ParsedScheme(ServerProfile.TlsMode.Https, input)
        val rest = input.substring(separator + 3)
        return when (input.substring(0, separator).lowercase()) {
            "http" -> ParsedScheme(ServerProfile.TlsMode.PlaintextHttp, rest)
            "https" -> ParsedScheme(ServerProfile.TlsMode.Https, rest)
            else -> null
        }
    }

    private sealed interface AuthorityResult {
        data class Ok(val authority: String) : AuthorityResult
        data class Failed(val problem: ServerInputProblem, val field: ServerAddressField) : AuthorityResult
    }

    /** Validates and strips the path/query/fragment; user-info is rejected (T8). */
    private fun extractAuthority(rest: String): AuthorityResult {
        if (rest.contains('#') || rest.contains('?')) {
            return AuthorityResult.Failed(ServerInputProblem.MALFORMED, ServerAddressField.PATH)
        }
        val slash = rest.indexOf('/')
        val authority = if (slash >= 0) rest.substring(0, slash) else rest
        val path = if (slash >= 0) rest.substring(slash) else ""
        if (path.isNotEmpty() && path != "/") {
            return AuthorityResult.Failed(ServerInputProblem.PATH_NOT_ALLOWED, ServerAddressField.PATH)
        }
        if (authority.contains('@')) {
            return AuthorityResult.Failed(ServerInputProblem.CREDENTIALS_NOT_ALLOWED, ServerAddressField.USER_INFO)
        }
        return AuthorityResult.Ok(authority)
    }

    private fun invalid(
        problem: ServerInputProblem,
        raw: String,
        field: ServerAddressField,
    ): ServerAddressResult.Invalid =
        ServerAddressResult.Invalid(DomainError.InvalidServerAddress(problem, raw, field))

    /** Splits `host`, `host:port`, or `[ipv6]` / `[ipv6]:port`. */
    private fun splitAuthority(authority: String): Authority? {
        if (authority.isEmpty()) return null

        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close <= 1) return null
            val host = authority.substring(1, close)
            val after = authority.substring(close + 1)
            return when {
                after.isEmpty() -> Authority(host, null)
                after.startsWith(":") -> Authority(host, after.substring(1))
                else -> null
            }
        }

        val colon = authority.indexOf(':')
        if (colon < 0) return Authority(authority, null)

        // An unbracketed IPv6 literal has multiple colons and is ambiguous.
        if (authority.indexOf(':', colon + 1) >= 0) return null
        return Authority(authority.substring(0, colon), authority.substring(colon + 1))
    }

    private data class Authority(val host: String, val portText: String?)
}

/**
 * Shared host/port grammar for manual entry and import links, kept in one place
 * so the two input paths cannot drift apart.
 */
internal object ServerHostGrammar {

    /** Characters that can never appear in a host (path/query/user-info delimiters). */
    private const val FORBIDDEN_HOST_CHARS = "/@?#[]"

    /** IPv6 literal: hex groups, colons, and possibly an embedded IPv4 tail. */
    private const val IPV6_HOST_CHARS = "0123456789abcdefABCDEF:."

    /** Extra hostname characters on top of letters and digits. */
    private const val HOSTNAME_EXTRA_CHARS = ".-_"

    /** Returns the problem with [host], or null when it is a valid host. */
    fun problem(host: String): ServerInputProblem? {
        if (host.isEmpty()) return ServerInputProblem.MISSING_HOST
        if (host.any { it.isWhitespace() }) return ServerInputProblem.INVALID_HOST
        if (host.any { it in FORBIDDEN_HOST_CHARS }) return ServerInputProblem.INVALID_HOST
        val valid = if (host.contains(':')) host.all(::isIpv6HostChar) else host.all(::isHostnameChar)
        return if (valid) null else ServerInputProblem.INVALID_HOST
    }

    private fun isIpv6HostChar(character: Char): Boolean = character in IPV6_HOST_CHARS

    private fun isHostnameChar(character: Char): Boolean =
        character.isLetterOrDigit() || character in HOSTNAME_EXTRA_CHARS

    /** Returns the problem with [portText], or null when it is a valid port. */
    fun portProblem(portText: String): ServerInputProblem? {
        if (portText.isEmpty() || !portText.all { it.isDigit() }) return ServerInputProblem.INVALID_PORT
        val value = portText.toIntOrNull() ?: return ServerInputProblem.PORT_OUT_OF_RANGE
        return if (value in 1..65535) null else ServerInputProblem.PORT_OUT_OF_RANGE
    }
}
