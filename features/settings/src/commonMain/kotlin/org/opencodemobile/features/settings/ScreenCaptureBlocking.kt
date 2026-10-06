package org.opencodemobile.features.settings

/**
 * Whether this platform can block screen capture independently of the
 * app-switcher cover (§7.3, criterion 4).
 *
 * The two protections are separate: masking is the in-app cover and is always
 * available; capture blocking depends on the OS. Android exposes `FLAG_SECURE`,
 * which also blocks screenshots and screen recording; iOS has no public API to
 * block captures, so the setting is not offered there. The settings screen uses
 * this capability to show or hide the capture-blocking control instead of
 * rendering an inert switch.
 */
public expect fun platformSupportsScreenCaptureBlocking(): Boolean
