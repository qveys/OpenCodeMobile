package org.opencodemobile.features.settings

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.stringResource
import org.opencodemobile.design.system.resources.Res
import org.opencodemobile.design.system.resources.settings_back
import org.opencodemobile.design.system.resources.settings_local_access_title
import org.opencodemobile.design.system.resources.settings_multitask_masking
import org.opencodemobile.design.system.resources.settings_multitask_masking_description
import org.opencodemobile.design.system.resources.settings_open
import org.opencodemobile.design.system.resources.settings_optional_biometrics
import org.opencodemobile.design.system.resources.settings_optional_biometrics_description
import org.opencodemobile.design.system.resources.settings_optional_biometrics_note
import org.opencodemobile.design.system.resources.settings_screen_capture_blocking
import org.opencodemobile.design.system.resources.settings_screen_capture_blocking_description
import org.opencodemobile.design.system.resources.settings_screen_capture_blocking_note
import org.opencodemobile.design.system.resources.settings_screen_capture_blocking_unavailable

/**
 * FR/EN copy for the §7.3 local-access settings surface, read from the
 * design-system `composeResources` catalogue.
 *
 * The catalogue is the project's single source of truth for copy, so this
 * surface no longer carries its own strings. [localAccessStrings] resolves
 * `Res.string.*` against the device locale exactly like every other screen; the
 * `values/` and `values-fr/` catalogues are kept in parity by
 * `SettingsStringsCatalogParityTest`.
 *
 * This used to be a device-locale switch over Kotlin literals because the iOS
 * app links a single *static* `iosAppHost` framework whose Compose resources
 * Xcode did not embed, so the first `stringResource(...)` aborted at startup.
 * The Xcode project now stages those resources through the Compose Gradle
 * plugin's `syncComposeResourcesForIos` task
 * (`scripts/ios/sync-compose-resources.sh`), so the catalogue works on iOS too.
 */
public data class LocalAccessStrings(
    public val open: String,
    public val back: String,
    public val title: String,
    public val optionalBiometrics: String,
    public val optionalBiometricsDescription: String,
    public val optionalBiometricsNote: String,
    public val multitaskMasking: String,
    public val multitaskMaskingDescription: String,
    public val screenCaptureBlocking: String,
    public val screenCaptureBlockingDescription: String,
    public val screenCaptureBlockingNote: String,
    public val screenCaptureBlockingUnavailable: String,
)

/** Resolves the local-access copy for the device locale from the catalogue. */
@Composable
public fun localAccessStrings(): LocalAccessStrings = LocalAccessStrings(
    open = stringResource(Res.string.settings_open),
    back = stringResource(Res.string.settings_back),
    title = stringResource(Res.string.settings_local_access_title),
    optionalBiometrics = stringResource(Res.string.settings_optional_biometrics),
    optionalBiometricsDescription = stringResource(Res.string.settings_optional_biometrics_description),
    optionalBiometricsNote = stringResource(Res.string.settings_optional_biometrics_note),
    multitaskMasking = stringResource(Res.string.settings_multitask_masking),
    multitaskMaskingDescription = stringResource(Res.string.settings_multitask_masking_description),
    screenCaptureBlocking = stringResource(Res.string.settings_screen_capture_blocking),
    screenCaptureBlockingDescription = stringResource(Res.string.settings_screen_capture_blocking_description),
    screenCaptureBlockingNote = stringResource(Res.string.settings_screen_capture_blocking_note),
    screenCaptureBlockingUnavailable = stringResource(Res.string.settings_screen_capture_blocking_unavailable),
)
