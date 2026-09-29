package org.opencodemobile.shared.networking.adapter

import io.ktor.client.HttpClientConfig

/**
 * Applies the outbound transport invariants of the connection policy to a
 * client under construction (`docs/ARCHITECTURE.md` §4.5, OPE-135):
 *
 * - The HTTP method allowlist is enforced by [installHttpMethodPolicy].
 * - Redirects are **not** followed. Ktor defaults to following them, including
 *   HTTPS→HTTP downgrades (`allowHttpsDowngrade = true`), so a server-supplied
 *   `Location` could move a request to a host the profile policy would have
 *   rejected. The client only ever talks to the profile's fixed `baseUrl`.
 *
 * Both platform factories (`createOpenCodeHttpClient`) apply this so no caller
 * can build a client that silently drops the policy.
 */
public fun HttpClientConfig<*>.applyConnectionTransportPolicy() {
    followRedirects = false
    installHttpMethodPolicy()
}
