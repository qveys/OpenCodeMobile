package org.opencodemobile.shared.networking.logging

import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging

/**
 * Installs Ktor's [`Logging`][Logging] plugin in a redaction-safe configuration
 * (threat T4).
 *
 * This is the **only** supported way to enable HTTP logging in
 * `shared/networking` / `shared/data`:
 *  - every emitted line is passed through [SanitizingHttpLogger], which redacts
 *    bearer/basic credentials and sensitive JSON body fields;
 *  - credential headers reported by the plugin are additionally flagged through
 *    Ktor's `sanitizeHeader`, so header dumps never include their values.
 *
 * Because redaction is bound into the logger and the plugin install here,
 * callers cannot accidentally log a secret by choosing a different level or
 * forgetting to scrub a value. A CI gate
 * (`scripts/check-no-secret-logging.sh`) rejects any raw `install(Logging)` or
 * verbose `LogLevel` use elsewhere in adapter code.
 *
 * @param level how much Ktor should attempt to log. `INFO` is the safe
 *   production default; bodies are still redacted even at `ALL`.
 * @param sanitizingSink receives already-redacted lines. Defaults to `println`.
 */
public fun HttpClientConfig<*>.installSanitizingLogging(
    level: LogLevel = LogLevel.INFO,
    sanitizingSink: (String) -> Unit = ::println,
) {
    install(Logging) {
        logger = SanitizingHttpLogger(sanitizingSink)
        this.level = level
        sanitizeHeader { headerName -> LogRedactor.isSensitiveHeader(headerName) }
    }
}