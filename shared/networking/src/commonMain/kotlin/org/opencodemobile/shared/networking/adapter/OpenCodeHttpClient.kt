package org.opencodemobile.shared.networking.adapter

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.opencodemobile.shared.networking.logging.installSanitizingLogging
import org.opencodemobile.shared.security.identity.ServerIdentityPinController

/**
 * Entry point for building the Ktor client used by `OpenCodeV2Adapter`.
 *
 * The default configuration installs JSON content negotiation (the generated
 * client calls `body()`), and enables HTTP logging only through the sanctioned
 * `installSanitizingLogging` factory, which routes every line through
 * `LogRedactor` and redacts credential headers. A raw Ktor `Logging` plugin
 * install is rejected by the T4 CI gate (`scripts/check-no-secret-logging.sh`).
 *
 * The connection transport policy (HTTP method allowlist, no redirect
 * following) is applied by [createOpenCodeHttpClient]'s platform actuals, so it
 * holds for every caller and not only for this wrapper.
 */
public object OpenCodeHttpClient {

    private val defaultJson: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /**
     * @param identityPin shared with the platform TLS engine; see
     *   [createOpenCodeHttpClient].
     * @param configure extra engine/client configuration applied last.
     */
    public fun create(
        identityPin: ServerIdentityPinController,
        configure: HttpClientConfig<*>.() -> Unit = {},
    ): HttpClient = createOpenCodeHttpClient(identityPin) {
        install(ContentNegotiation) {
            json(defaultJson)
        }
        installSanitizingLogging(level = LogLevel.INFO)
        configure()
    }
}