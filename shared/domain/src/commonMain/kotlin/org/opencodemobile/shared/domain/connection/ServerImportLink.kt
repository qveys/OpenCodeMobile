package org.opencodemobile.shared.domain.connection

/** How a [ServerImport] reached the app. Both share one review screen. */
public enum class ServerImportSource {
    /** Decoded from a QR code captured with the in-app scanner. */
    QrCode,

    /** Delivered by the OS as a deep link. */
    DeepLink,
}

/**
 * A server profile candidate decoded from an import link (deep link or QR code)
 * and not yet persisted (`docs/ARCHITECTURE.md` §"Server profile import").
 *
 * The link is only a transport: it never writes a profile directly, and it
 * never carries a long-lived bearer credential. Persisting this candidate is
 * the job of the import review screen, after the user confirms the full target.
 */
public data class ServerImport(
    /** The profile the link described, already normalised by [ServerAddressParser]. */
    public val profile: ServerProfile,
    /** Where the link came from. */
    public val source: ServerImportSource,
    /**
     * Optional TOFU fingerprint carried by the link. When present it is shown on
     * the review screen, but it does **not** replace the live first-connect
     * identity check (T1): the pin is still confirmed against what the server
     * presents on the wire.
     */
    public val fingerprint: ServerFingerprint? = null,
    /**
     * Optional short-lived, single-use pairing code. It is exchanged for a
     * credential over a direct TLS connection after the user confirms the
     * import; it is never a usable credential on its own.
     */
    public val pairingCode: String? = null,
) {
    /** `host:port`, shown in full on the review screen. */
    public val authority: String
        get() = profile.authority
}

/**
 * Parses the import links that deep links and QR codes decode to.
 *
 * The parser is strict and fail-closed: anything unrecognised or malformed
 * returns null and is discarded silently, so a bad link can never partially
 * populate or clear an existing profile. It also refuses links that embed a
 * long-lived credential, because links leak through browser history, share
 * sheets, clipboards, and OS link logs (T8).
 *
 * Two link shapes are accepted:
 * - the app's own custom scheme, `opencodemobile://import?...` (and
 *   `.../connect`), which is what the QR fallback encodes; and
 * - a verified HTTPS link whose host is passed in [verifiedImportHosts] and
 *   whose path is `/import` or `/connect` (Android App Link / iOS Universal
 *   Link). The default is empty, so no HTTPS host is trusted until the project
 *   owns a verified domain.
 */
public object ServerImportLinkParser {
    /** The app's custom link scheme. */
    public const val SCHEME: String = "opencodemobile"

    private val ACTIONS = setOf("import", "connect")
    private const val PATH_IMPORT = "/import"
    private const val PATH_CONNECT = "/connect"

    /** Query keys that would smuggle a credential into the link; presence discards it. */
    private val CREDENTIAL_KEYS = setOf(
        "token", "credential", "credentials", "bearer", "apikey", "api_key",
        "access_token", "secret", "password", "pass", "key",
    )

    private const val MAX_PAIRING_CODE_LENGTH = 128

    /**
     * Parses [raw] and returns the described [ServerImport], or null when the
     * link is unrecognised or malformed (callers discard null silently).
     *
     * @param source which surface produced the link (QR scanner vs deep link).
     * @param verifiedImportHosts HTTPS hosts allowed to act as verified import
     *   links. Empty by default.
     */
    public fun parseOrNull(
        raw: String,
        source: ServerImportSource = ServerImportSource.DeepLink,
        verifiedImportHosts: Set<String> = emptySet(),
    ): ServerImport? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        val schemeSeparator = trimmed.indexOf("://")
        if (schemeSeparator <= 0) return null
        val scheme = trimmed.substring(0, schemeSeparator).lowercase()
        val rest = trimmed.substring(schemeSeparator + 3)

        val queryStart = rest.indexOf('?')
        val head = if (queryStart >= 0) rest.substring(0, queryStart) else rest
        val query = if (queryStart >= 0) rest.substring(queryStart + 1) else ""

        val accepted = when (scheme) {
            SCHEME -> head.trimEnd('/').lowercase() in ACTIONS
            "https" -> {
                val slash = head.indexOf('/')
                if (slash <= 0) return null
                val host = head.substring(0, slash).lowercase()
                val path = head.substring(slash).trimEnd('/')
                host in verifiedImportHosts && (path == PATH_IMPORT || path == PATH_CONNECT)
            }
            else -> false
        }
        if (!accepted) return null

        val params = parseQuery(query) ?: return null
        if (params.keys.any { it.lowercase() in CREDENTIAL_KEYS }) return null

        val host = params["host"]?.trim().orEmpty()
        if (host.isEmpty()) return null

        val port = params["port"]?.let { rawPort ->
            rawPort.trim().toIntOrNull() ?: return null
        } ?: ServerAddressParser.DEFAULT_PORT

        val tls = when (params["tls"]?.lowercase()) {
            null, "", "https" -> ServerProfile.TlsMode.Https
            "http" -> ServerProfile.TlsMode.PlaintextHttp
            else -> return null
        }

        // Reuse the manual-entry parser so both paths apply identical host/port rules.
        val schemeForAddress = if (tls == ServerProfile.TlsMode.PlaintextHttp) "http" else "https"
        val authority = if (host.contains(':')) "[$host]:$port" else "$host:$port"
        val parsed = ServerAddressParser.parse("$schemeForAddress://$authority")
        val profile = (parsed as? ServerAddressResult.Valid)?.profile ?: return null

        val label = params["label"]?.trim()?.takeIf { it.isNotEmpty() }
        val rawFingerprint = params["fp"] ?: params["fingerprint"]
        val fingerprint = if (rawFingerprint != null) {
            try {
                ServerFingerprint.fromHex(rawFingerprint)
            } catch (_: IllegalArgumentException) {
                // A malformed fingerprint makes the whole link untrustworthy.
                return null
            }
        } else {
            null
        }

        val pairingCode = params["code"]?.takeIf { it.isNotEmpty() }
        if (pairingCode != null && pairingCode.length > MAX_PAIRING_CODE_LENGTH) return null

        return ServerImport(
            profile = profile.copy(label = label),
            source = source,
            fingerprint = fingerprint,
            pairingCode = pairingCode,
        )
    }

    /**
     * Parses a `k=v&k=v` query into a decoded map. Returns null when the query
     * contains an invalid percent-escape or an empty key.
     */
    private fun parseQuery(query: String): Map<String, String>? {
        if (query.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val equals = pair.indexOf('=')
            val rawKey = if (equals >= 0) pair.substring(0, equals) else pair
            val rawValue = if (equals >= 0) pair.substring(equals + 1) else ""
            val key = percentDecode(rawKey) ?: return null
            val value = percentDecode(rawValue) ?: return null
            if (key.isEmpty()) return null
            result[key] = value
        }
        return result
    }

    /** Decodes `%XX` escapes; returns null on a malformed escape. '+' is left literal. */
    private fun percentDecode(value: String): String? {
        val out = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (character == '%') {
                if (index + 2 >= value.length) return null
                val hex = value.substring(index + 1, index + 3)
                val code = hex.toIntOrNull(16) ?: return null
                out.append(code.toChar())
                index += 3
            } else {
                out.append(character)
                index += 1
            }
        }
        return out.toString()
    }
}
