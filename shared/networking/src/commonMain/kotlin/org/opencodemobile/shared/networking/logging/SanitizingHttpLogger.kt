package org.opencodemobile.shared.networking.logging

import io.ktor.client.plugins.logging.Logger

/**
 * Ktor [Logger] that sanitizes every message through [LogRedactor.redact]
 * before handing it to [sink].
 *
 * Pass an [`Logging`][io.ktor.client.plugins.logging.Logging] plugin configured
 * with this logger (preferably via [installSanitizingLogging]) so no HTTP log
 * line can contain a raw `Authorization` header, bearer/basic credential or
 * prompt body. Redaction happens inside the logger itself, so it cannot be
 * bypassed by a call site that forgets to scrub.
 *
 * @param sink destination for already-redacted lines. Defaults to `println`,
 *   which Ktor's own default logger also uses.
 */
public class SanitizingHttpLogger(
    private val sink: (String) -> Unit = ::println,
) : Logger {

    override fun log(message: String) {
        sink(LogRedactor.redact(message))
    }
}