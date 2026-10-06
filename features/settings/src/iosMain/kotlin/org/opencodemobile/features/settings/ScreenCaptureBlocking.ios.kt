package org.opencodemobile.features.settings

/**
 * iOS has no public API to block screenshots or screen recording, so the setting
 * is not offered. The multitask masking (the `scenePhase` cover) still applies;
 * it hides the app-switcher snapshot and is the only protection iOS exposes.
 */
public actual fun platformSupportsScreenCaptureBlocking(): Boolean = false
