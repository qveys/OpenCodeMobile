package org.opencodemobile.shared.security.identity

import org.opencodemobile.shared.domain.connection.ServerFingerprint

/**
 * The single pin-comparison rule shared by the Android `X509TrustManager` and
 * the iOS `URLSession` challenge delegate, so both platforms enforce the same
 * decision and it can be unit-tested without a TLS stack.
 *
 * `null` [pinned] is TOFU capture mode (first contact, or a plaintext profile
 * with no certificate): nothing is enforced yet, and the presented value is
 * reported for display. A non-null [pinned] must match exactly.
 */
public object SpkiPinCheck {

    /** True when [presented] is acceptable for [pinned]. */
    public fun matches(pinned: ServerFingerprint?, presented: ServerFingerprint): Boolean =
        pinned == null || pinned == presented
}