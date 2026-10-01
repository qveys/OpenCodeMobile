package org.opencodemobile.shared.domain.connection

/**
 * A server profile decoded from a deep link or QR code
 * (`docs/ARCHITECTURE.md` §"Server profile import"). It is a *candidate*: the
 * import flow must show it on the review screen and get an explicit
 * confirmation before it is persisted. It never carries a credential (T8).
 */
public data class ImportedServerProfile(
    /** Bare host name or IP literal, without scheme, port, brackets, or path. */
    public val host: String,
    /** TCP port; defaults to [ServerAddressParser.DEFAULT_PORT] when the link omits it. */
    public val port: Int,
    /** Transport security declared by the link (defaults to HTTPS). */
    public val tls: ServerProfile.TlsMode = ServerProfile.TlsMode.Https,
    /** Optional display label from the link. */
    public val label: String? = null,
    /** Optional TOFU fingerprint from the link, shown for confirmation (T1). */
    public val fingerprint: ServerFingerprint? = null,
) {
    init {
        require(host.isNotBlank()) { "ImportedServerProfile.host must not be blank" }
        require(port in 1..65535) { "ImportedServerProfile.port out of range: $port" }
    }

    /** `host:port`, with IPv6 hosts bracketed. */
    public val authority: String
        get() = if (host.contains(':')) "[$host]:$port" else "$host:$port"

    /** Deterministic profile id, so a re-import can be detected (no silent overwrite). */
    public fun profileId(): String = "${host.lowercase()}:$port"

    /** Builds the storable profile; persistence still requires the review confirmation. */
    public fun toProfile(id: String = profileId()): ServerProfile = ServerProfile(
        id = id,
        host = host,
        port = port,
        label = label,
        tls = tls,
    )
}

/** Outcome of decoding a deep link or QR payload. */
public sealed interface ServerImportResult {
    /** The payload decoded to a candidate profile. */
    public data class Valid(public val profile: ImportedServerProfile) : ServerImportResult

    /**
     * The payload was malformed. It is discarded and must not partially
     * populate or clear an existing profile (`docs/ARCHITECTURE.md`
     * §"Unrecognized or malformed import links … are discarded silently").
     */
    public data class Malformed(public val error: DomainError.InvalidImportLink) : ServerImportResult
}

/**
 * Decodes the versioned server-import link that a QR code encodes
 * (`docs/ARCHITECTURE.md` §"Server profile import").
 *
 * A QR code is purely a transport for the same link the deep-link handler
 * consumes, so both paths share this parser.
 *
 * Canonical v1 form:
 *
 * ```
 * opencodemobile://import?host=192.168.1.10&port=4096&label=Home%20server&tls=https&fp=<sha256-hex>&v=1
 * ```
 *
 * Parameters (all optional except `host`):
 * - `host` — host name or IP literal (IPv6 without brackets).
 * - `port` — 1..65535; defaults to [ServerAddressParser.DEFAULT_PORT].
 * - `tls` — `https` (default) or `http`.
 * - `label` — display label.
 * - `fp` — SHA-256 SPKI fingerprint, hex with or without colons (T1).
 * - `v` — link format version; must be `1` when present.
 *
 * Credentials are never accepted in a link: a parameter named `token`,
 * `password`, `secret`, `bearer`, `authorization`, `apikey`, or `credential`
 * makes the whole link malformed (T8).
 */
public object ServerImportLink {

    /** Custom URI scheme used by the app for import links. */
    public const val SCHEME: String = "opencodemobile"

    /** Authority/path that marks an import link. */
    public const val HOST: String = "import"

    /** The only link format version this app understands. */
    public const val SUPPORTED_VERSION: Int = 1

    private const val PARAM_VERSION = "v"
    private const val PARAM_HOST = "host"
    private const val PARAM_PORT = "port"
    private const val PARAM_TLS = "tls"
    private const val PARAM_LABEL = "label"
    private const val PARAM_FINGERPRINT = "fp"

    private val ALLOWED_PARAMS = setOf(
        PARAM_VERSION, PARAM_HOST, PARAM_PORT, PARAM_TLS, PARAM_LABEL, PARAM_FINGERPRINT,
    )

    private val CREDENTIAL_PARAMS = setOf(
        "token", "password", "pass", "secret", "bearer", "authorization", "apikey", "api_key",
        "key", "credential", "credentials", "creds",
    )

    /**
     * Decodes [raw], returning a candidate profile or a typed validation error.
     *
     * The branching is the link grammar itself; the rule is suppressed for this
     * migrated parser (the baseline-vs-refactor policy is tracked in OPE-221).
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    public fun parse(raw: String): ServerImportResult {
        val input = raw.trim()
        if (input.isEmpty()) return malformed(ServerInputProblem.BLANK, raw)

        val schemeSeparator = input.indexOf("://")
        if (schemeSeparator <= 0) return malformed(ServerInputProblem.MALFORMED, raw)
        if (!input.substring(0, schemeSeparator).equals(SCHEME, ignoreCase = true)) {
            return malformed(ServerInputProblem.UNKNOWN_SCHEME, raw)
        }

        val rest = input.substring(schemeSeparator + 3)
        if (rest.contains('#')) return malformed(ServerInputProblem.MALFORMED, raw)

        val queryStart = rest.indexOf('?')
        val target = if (queryStart >= 0) rest.substring(0, queryStart) else rest
        val query = if (queryStart >= 0) rest.substring(queryStart + 1) else ""

        if (target.trimEnd('/') != HOST) return malformed(ServerInputProblem.MALFORMED, raw)
        if (query.isEmpty()) return malformed(ServerInputProblem.MISSING_HOST, raw)

        val params = parseQuery(query) ?: return malformed(ServerInputProblem.MALFORMED, raw)

        if (params.keys.any { it in CREDENTIAL_PARAMS }) {
            return malformed(ServerInputProblem.CREDENTIALS_NOT_ALLOWED, raw)
        }
        params.keys.firstOrNull { it !in ALLOWED_PARAMS }?.let {
            return malformed(ServerInputProblem.UNKNOWN_PARAMETER, raw)
        }

        params[PARAM_VERSION]?.let { version ->
            if (version != SUPPORTED_VERSION.toString()) {
                return malformed(ServerInputProblem.UNSUPPORTED_VERSION, raw)
            }
        }

        val host = params[PARAM_HOST]?.takeIf { it.isNotBlank() }
            ?: return malformed(ServerInputProblem.MISSING_HOST, raw)
        ServerHostGrammar.problem(host)?.let { return malformed(it, raw) }

        val port = when (val portText = params[PARAM_PORT]) {
            null -> ServerAddressParser.DEFAULT_PORT
            else -> {
                ServerHostGrammar.portProblem(portText)?.let { return malformed(it, raw) }
                portText.toIntOrNull() ?: return malformed(ServerInputProblem.INVALID_PORT, raw)
            }
        }

        val tls = when (params[PARAM_TLS]?.lowercase()) {
            null, "https" -> ServerProfile.TlsMode.Https
            "http" -> ServerProfile.TlsMode.PlaintextHttp
            else -> return malformed(ServerInputProblem.INVALID_TLS, raw)
        }

        val fingerprint = when (val fpText = params[PARAM_FINGERPRINT]) {
            null, "" -> null
            else -> try {
                ServerFingerprint.fromHex(fpText)
            } catch (_: IllegalArgumentException) {
                return malformed(ServerInputProblem.FINGERPRINT_INVALID, raw)
            }
        }

        return ServerImportResult.Valid(
            ImportedServerProfile(
                host = host,
                port = port,
                tls = tls,
                label = params[PARAM_LABEL]?.takeIf { it.isNotBlank() },
                fingerprint = fingerprint,
            ),
        )
    }

    /** Convenience: the decoded candidate, or null when [raw] is malformed. */
    public fun parseOrNull(raw: String): ImportedServerProfile? =
        (parse(raw) as? ServerImportResult.Valid)?.profile

    private fun malformed(problem: ServerInputProblem, raw: String): ServerImportResult.Malformed =
        ServerImportResult.Malformed(DomainError.InvalidImportLink(problem, raw))

    /** Splits `a=1&b=2` into a case-insensitive map; null on a malformed escape. */
    private fun parseQuery(query: String): Map<String, String>? {
        val params = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val equals = pair.indexOf('=')
            val name = percentDecode(if (equals >= 0) pair.substring(0, equals) else pair) ?: return null
            val value = percentDecode(if (equals >= 0) pair.substring(equals + 1) else "") ?: return null
            if (name.isEmpty() || params.containsKey(name.lowercase())) return null
            params[name.lowercase()] = value
        }
        return params
    }

    /** Percent-decodes `%XX` and `+`; returns null on an invalid `%` escape. */
    private fun percentDecode(value: String): String? {
        val builder = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            when (val char = value[index]) {
                '+' -> {
                    builder.append(' ')
                    index++
                }

                '%' -> {
                    if (index + 2 >= value.length) return null
                    val code = value.substring(index + 1, index + 3).toIntOrNull(16) ?: return null
                    builder.append(code.toChar())
                    index += 3
                }

                else -> {
                    builder.append(char)
                    index++
                }
            }
        }
        return builder.toString()
    }
}
