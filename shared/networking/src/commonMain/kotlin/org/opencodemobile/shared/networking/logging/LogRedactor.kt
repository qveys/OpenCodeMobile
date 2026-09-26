package org.opencodemobile.shared.networking.logging

/**
 * Redaction rules for HTTP log output (threat T4 — "token leakage via logging").
 *
 * This is a *pure* function set with no logging side effects, so it can be
 * exhaustively unit-tested. [SanitizingHttpLogger] and
 * [installSanitizingLogging] are the only supported way to turn on Ktor HTTP
 * logging in `shared/networking` / `shared/data`; they route every emitted line
 * through [redact] so a future call site cannot regress the redaction by
 * forgetting to scrub a value.
 *
 * Covered secret-bearing surfaces:
 *  - credential request/response headers (`Authorization`, cookies, API keys);
 *  - bearer/basic credential values appearing anywhere in a line;
 *  - JSON body fields carrying prompt content or credentials.
 *
 * Structural metadata (method, URL, status code, content type, byte size) is
 * intentionally preserved so logs stay useful for debugging.
 */
public object LogRedactor {

    /** Marker written in place of any redacted value. */
    public const val REDACTED: String = "<redacted>"

    /** Header names whose values must never be logged verbatim. */
    private val SENSITIVE_HEADERS: Set<String> = setOf(
        "authorization",
        "proxy-authorization",
        "cookie",
        "set-cookie",
        "x-api-key",
        "api-key",
        "x-auth-token",
        "x-opencode-token",
        "x-session-token",
    )

    /** Body fields whose values must never be logged verbatim. */
    private val SENSITIVE_BODY_KEYS: Set<String> = setOf(
        "prompt",
        "prompts",
        "content",
        "message",
        "messages",
        "text",
        "password",
        "passphrase",
        "token",
        "access_token",
        "refresh_token",
        "id_token",
        "secret",
        "client_secret",
        "api_key",
        "apikey",
        "authorization",
        "credential",
        "credentials",
        "diff",
        "patch",
    )

    private val headerValuePattern: Regex = buildSensitiveHeaderValueRegex()

    private val bearerPattern: Regex =
        Regex("(?i)(\\bBearer\\s+)([A-Za-z0-9._~+/=-]{4,})")

    private val basicPattern: Regex =
        Regex("(?i)(\\bBasic\\s+)([A-Za-z0-9+/=]{4,})")

    private val jsonSecretPattern: Regex = buildSensitiveJsonRegex()

    /**
     * True when [headerName] carries a credential and its value must be
     * redacted before logging. Used as the Ktor `Logging` plugin
     * `sanitizeHeader` predicate, and safe to reuse for any other header dump.
     */
    public fun isSensitiveHeader(headerName: String): Boolean =
        headerName.trim().lowercase() in SENSITIVE_HEADERS

    /**
     * Returns [input] with every secret-bearing header value, bearer/basic
     * credential and sensitive JSON body field replaced by [REDACTED].
     *
     * Safe to call on arbitrary text; when nothing matches the input is
     * returned unchanged.
     */
    public fun redact(input: String): String {
        var out = headerValuePattern.replace(input) { match -> match.groupValues[1] + REDACTED }
        out = bearerPattern.replace(out) { match -> match.groupValues[1] + REDACTED }
        out = basicPattern.replace(out) { match -> match.groupValues[1] + REDACTED }
        out = jsonSecretPattern.replace(out) { match -> "\"${match.groupValues[1]}\":\"$REDACTED\"" }
        return out
    }

    private fun buildSensitiveHeaderValueRegex(): Regex {
        val names = SENSITIVE_HEADERS.joinToString("|") { Regex.escape(it) }
        // Matches e.g. "Authorization: ...", "-> Authorization: ...", "x-api-key: ...".
        // The value runs to end of line so no payload escapes.
        return Regex("(?im)((?:^|[\\s>])(?:$names)[\"']?\\s*:\\s*)([^\\r\\n]*)")
    }

    private fun buildSensitiveJsonRegex(): Regex {
        val keys = SENSITIVE_BODY_KEYS.joinToString("|") { Regex.escape(it) }
        // Matches a quoted sensitive key and its scalar/array/object value:
        //   "prompt":"..."   "messages":[...]   "token":{...}   "api_key":123
        return Regex(
            "(?i)\"($keys)\"\\s*:\\s*(\"(?:\\\\.|[^\"\\\\])*\"|\\[[^\\]]*]|\\{[^{}]*}|[^,}\\]]+)",
        )
    }
}