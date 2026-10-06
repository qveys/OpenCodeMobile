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
import org.opencodemobile.shared.domain.localaccess.LocalAccessSettings

/**
 * The §7.3 local-access settings surface, bilingual FR/EN through
 * [LocalAccessStrings].
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
    actions: LocalAccessSettingsActions,
    strings: LocalAccessStrings,
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
            LocalAccessSettingsHeader(strings, onBack)
            OptionalBiometricsSection(state.optionalBiometricsEnabled, strings, actions)
            MultitaskMaskingSection(state.multitaskMaskingEnabled, strings, actions)
            ScreenCaptureBlockingSection(
                enabled = state.screenCaptureBlockingEnabled,
                supported = screenCaptureBlockingSupported,
                strings = strings,
                onCheckedChange = actions.onScreenCaptureBlockingChange,
            )
        }
    }
}

@Composable
private fun LocalAccessSettingsHeader(strings: LocalAccessStrings, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(onClick = onBack) {
            Text(strings.back)
        }
        Text(
            text = strings.title,
            style = MaterialTheme.typography.headlineSmall,
        )
    }
}

@Composable
private fun OptionalBiometricsSection(
    enabled: Boolean,
    strings: LocalAccessStrings,
    actions: LocalAccessSettingsActions,
) {
    LocalAccessToggle(
        title = strings.optionalBiometrics,
        description = strings.optionalBiometricsDescription,
        checked = enabled,
        onCheckedChange = actions.onOptionalBiometricsChange,
    )
    Text(
        text = strings.optionalBiometricsNote,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun MultitaskMaskingSection(
    enabled: Boolean,
    strings: LocalAccessStrings,
    actions: LocalAccessSettingsActions,
) {
    LocalAccessToggle(
        title = strings.multitaskMasking,
        description = strings.multitaskMaskingDescription,
        checked = enabled,
        onCheckedChange = actions.onMultitaskMaskingChange,
    )
}

/**
 * Renders the capture-blocking control only when the platform supports it. The
 * explicit note on unsupported platforms replaces a switch that would have no
 * effect (review finding F1).
 */
@Composable
private fun ScreenCaptureBlockingSection(
    enabled: Boolean,
    supported: Boolean,
    strings: LocalAccessStrings,
    onCheckedChange: (Boolean) -> Unit,
) {
    if (supported) {
        LocalAccessToggle(
            title = strings.screenCaptureBlocking,
            description = strings.screenCaptureBlockingDescription,
            checked = enabled,
            onCheckedChange = onCheckedChange,
        )
        Text(
            text = strings.screenCaptureBlockingNote,
            style = MaterialTheme.typography.bodySmall,
        )
    } else {
        Text(
            text = strings.screenCaptureBlockingUnavailable,
            style = MaterialTheme.typography.bodySmall,
        )
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
