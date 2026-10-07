package org.opencodemobile.features.settings

/** User intents of the local-access settings screen, grouped to keep the call site readable. */
public data class LocalAccessSettingsActions(
    public val onOptionalBiometricsChange: (Boolean) -> Unit,
    public val onMultitaskMaskingChange: (Boolean) -> Unit,
    public val onScreenCaptureBlockingChange: (Boolean) -> Unit,
)
