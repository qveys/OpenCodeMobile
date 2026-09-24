package org.opencodemobile.shared.networking.adapter

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.opencodemobile.shared.security.identity.ServerIdentityPinController

/**
 * Entry point for building the Ktor client used by `OpenCodeV2Adapter`.
 *
 * The default configuration installs JSON content negotiation (the generated
 * client calls `body()`) and a logging plugin that redacts the `Authorization`
 * header, so the server credential can never be written to logs (T4).
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
        install(Logging) {
            level = LogLevel.INFO
            sanitizeHeader { header ->
                header.equals(HEADER_AUTHORIZATION, ignoreCase = true)
            }
        }
        configure()
    }

    private const val HEADER_AUTHORIZATION = "Authorization"
}