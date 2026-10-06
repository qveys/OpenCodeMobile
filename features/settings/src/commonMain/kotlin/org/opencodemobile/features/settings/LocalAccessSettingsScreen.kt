package org.opencodemobile.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.opencodemobile.design.system.resources.Res
import org.opencodemobile.design.system.resources.settings_back
import org.opencodemobile.design.system.resources.settings_local_access_title
import org.opencodemobile.design.system.resources.settings_multitask_masking
import org.opencodemobile.design.system.resources.settings_multitask_masking_description
import org.opencodemobile.design.system.resources.settings_optional_biometrics
import org.opencodemobile.design.system.resources.settings_optional_biometrics_description
import org.opencodemobile.design.system.resources.settings_optional_biometrics_note
import org.opencodemobile.design.system.resources.settings_screen_capture_blocking
import org.opencodemobile.design.system.resources.settings_screen_capture_blocking_description
import org.opencodemobile.design.system.resources.settings_screen_capture_blocking_note
import org.opencodemobile.design.system.resources.settings_screen_capture_blocking_unavailable
import org.opencodemobile.shared.domain.localaccess.LocalAccessSettings

/**
 * The §7.3 local-access settings surface (bilingual FR/EN through the
 * design-system string catalogue).
 *
 * It renders the device preferences and forwards every change to the presenter;
 * it holds no state itself and never reads a platform API. The Android and iOS
 * hosts render the exact same screen through [LocalAccessSettingsHost].
 *
 * [screenCaptureBlockingSupported] is the platform capability. When a platform
 * cannot block captures (iOS), the capture-blocking control is **not rendered**
 * — instead of showing a switch that would do nothing — and an explicit note
 * explains why. Masking is always offered.
 */
@Composable
public fun LocalAccessSettingsScreen(
    state: LocalAccessSettings,
    screenCaptureBlockingSupported: Boolean,
    onOptionalBiometricsChange: (Boolean) -> Unit,
    onMultitaskMaskingChange: (Boolean) -> Unit,
    onScreenCaptureBlockingChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onBack) {
                    Text(stringResource(Res.string.settings_back))
                }
                Text(
                    text = stringResource(Res.string.settings_local_access_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            LocalAccessToggle(
                title = stringResource(Res.string.settings_optional_biometrics),
                description = stringResource(Res.string.settings_optional_biometrics_description),
                checked = state.optionalBiometricsEnabled,
                onCheckedChange = onOptionalBiometricsChange,
            )
            Text(
                text = stringResource(Res.string.settings_optional_biometrics_note),
                style = MaterialTheme.typography.bodySmall,
            )
            LocalAccessToggle(
                title = stringResource(Res.string.settings_multitask_masking),
                description = stringResource(Res.string.settings_multitask_masking_description),
                checked = state.multitaskMaskingEnabled,
                onCheckedChange = onMultitaskMaskingChange,
            )
            if (screenCaptureBlockingSupported) {
                LocalAccessToggle(
                    title = stringResource(Res.string.settings_screen_capture_blocking),
                    description = stringResource(Res.string.settings_screen_capture_blocking_description),
                    checked = state.screenCaptureBlockingEnabled,
                    onCheckedChange = onScreenCaptureBlockingChange,
                )
                Text(
                    text = stringResource(Res.string.settings_screen_capture_blocking_note),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text(
                    text = stringResource(Res.string.settings_screen_capture_blocking_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun LocalAccessToggle(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(text = description, style = MaterialTheme.typography.bodyMedium)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
