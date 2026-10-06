package org.opencodemobile.features.settings

/**
 * Android supports screen-capture blocking through `WindowManager`'s
 * `FLAG_SECURE`, applied by `androidApp`'s `PrivacyShield` when the user opts
 * in. On Android that flag also blocks screenshots and screen recording.
 */
public actual fun platformSupportsScreenCaptureBlocking(): Boolean = true
