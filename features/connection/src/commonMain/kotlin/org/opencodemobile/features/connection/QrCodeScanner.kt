package org.opencodemobile.features.connection

/**
 * Port for the platform camera adapter that captures a QR code and returns the
 * decoded text payload.
 *
 * QR is only a transport for the same import link the deep-link handler
 * consumes (`docs/ARCHITECTURE.md` §"Server profile import"): the feature never
 * interprets the camera image itself, it hands the decoded payload to
 * [ConnectionSetupController.submitScannedPayload]. Returning `null` means the
 * user cancelled the capture.
 *
 * The Android (CameraX/ML Kit) and iOS (AVFoundation) adapters implement this
 * port and are supplied by the composition root, so the feature module stays
 * free of platform camera dependencies and can be unit-tested with a fake.
 */
public fun interface QrCodeScanner {
    /** Opens the scanner UI and resolves with the decoded payload, or null when cancelled. */
    public suspend fun scan(): String?
}
