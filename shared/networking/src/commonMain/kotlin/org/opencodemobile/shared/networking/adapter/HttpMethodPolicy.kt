package org.opencodemobile.shared.networking.adapter

import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.api.createClientPlugin
import org.opencodemobile.shared.domain.connection.ConnectionPolicyException
import org.opencodemobile.shared.domain.connection.HttpConnectionPolicy

/**
 * Installs the outbound HTTP method policy (`docs/ARCHITECTURE.md` §4.5,
 * OPE-135).
 *
 * A request whose method is outside [HttpConnectionPolicy.allowedMethods] is
 * refused with [ConnectionPolicyException.MethodNotAllowed] in the request
 * pipeline, before it reaches the engine or the network. The app is
 * outbound-only and the generated client uses exactly `GET`, `POST` and
 * `DELETE`; this makes that surface explicit and fails closed if a future
 * caller builds a request by hand.
 */
public fun HttpClientConfig<*>.installHttpMethodPolicy() {
    install(
        createClientPlugin("HttpMethodPolicy") {
            onRequest { request, _ ->
                val method = request.method.value
                if (!HttpConnectionPolicy.isMethodAllowed(method)) {
                    throw ConnectionPolicyException.MethodNotAllowed(method)
                }
            }
        },
    )
}
