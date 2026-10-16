package org.opencodemobile.shared.networking.adapter

import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.util.encodeBase64

/**
 * Installs the outbound `Authorization: Basic` header for the Traefik/Dokploy
 * reverse-proxy gate that sits in front of a self-hosted OpenCode server.
 *
 * The header is attached preemptively on every request — there is no
 * challenge round-trip, because the profile either needs this gate or it
 * doesn't — and only when [controller] currently holds a credential, so a
 * profile that never configured one sends no header at all.
 *
 * This is a separate concern from the server's own bearer-token auth (which
 * the generated client attaches itself via `authTokenProvider`); both happen
 * to be carried on the same `Authorization` header name, so a profile that
 * configures both a reverse-proxy credential and a server bearer token is out
 * of scope for V1 (the proxy is expected to consume/strip its own challenge
 * before forwarding).
 */
public fun HttpClientConfig<*>.installBasicAuthHeader(controller: BasicAuthCredentialController) {
    install(
        createClientPlugin("OpenCodeBasicAuthHeader") {
            onRequest { request, _ ->
                controller.currentCredential()?.let { credential ->
                    val token = "${credential.username}:${credential.password}".encodeBase64()
                    request.header(HttpHeaders.Authorization, "Basic $token")
                }
            }
        },
    )
}
