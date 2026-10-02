package org.opencodemobile.features.permissions

import org.opencodemobile.shared.domain.permission.PermissionPolicy

/**
 * The permission deep links (V1-06, OP4).
 *
 * The only link the app accepts is
 * `opencodemobile://permission/confirmation/{requestId}`: it opens the foreground
 * confirmation screen for that request and **carries nothing else**. A notification
 * tap and an external link both go through here.
 *
 * Parsing happens on the raw string rather than a platform `Uri`, so the exact same
 * rule runs on Android and iOS and can be unit-tested on the JVM. It never reads a
 * query string, a fragment or any parameter that could name a decision: there is no
 * code path from a deep link to `approve`/`deny`. Approval is only reachable from
 * the in-app confirmation screen, after the biometric gate.
 */
public object PermissionDeepLink {

    /**
     * The route prefix a notification tap opens. It is exactly the policy's route
     * prefix so the notification plan and the parser cannot drift apart.
     */
    public const val CONFIRMATION_ROUTE_PREFIX: String = PermissionPolicy.CONFIRMATION_ROUTE_PREFIX

    /**
     * Returns the request id when [raw] is exactly the confirmation route for one
     * request, otherwise null.
     *
     * A query string, a fragment, a trailing slash, an extra path segment or any
     * other host/path is rejected. The id is treated as an opaque single segment:
     * a link can only ever open the confirmation screen, never apply a decision.
     */
    public fun confirmationRequestId(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (!value.startsWith(CONFIRMATION_ROUTE_PREFIX)) return null
        val requestId = value.removePrefix(CONFIRMATION_ROUTE_PREFIX)
        if (requestId.isEmpty()) return null
        if (requestId.length > MAX_REQUEST_ID_LENGTH) return null
        // No nesting, no query, no fragment and no whitespace: a single opaque id.
        val isOpaqueId = requestId.none { it == '/' || it == '?' || it == '#' || it == '&' || it.isWhitespace() }
        return requestId.takeIf { isOpaqueId }
    }

    private const val MAX_REQUEST_ID_LENGTH: Int = 256
}
