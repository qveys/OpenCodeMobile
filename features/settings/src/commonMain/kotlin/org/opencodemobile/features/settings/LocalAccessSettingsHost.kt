package org.opencodemobile.features.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.opencodemobile.design.system.resources.Res
import org.opencodemobile.design.system.resources.settings_open

/**
 * §7.3 local-access route: resolves the settings presenter from Koin, observes
 * its state and renders [LocalAccessSettingsScreen].
 *
 * The presenter is bound by `SettingsModule.koinModule`; this is the composition
 * site that was missing, so a user can actually change the three switches.
 */
@Composable
public fun LocalAccessSettingsRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val controller: LocalAccessSettingsController = koinInject()
    val state by controller.state.collectAsState()

    LocalAccessSettingsScreen(
        state = state,
        screenCaptureBlockingSupported = platformSupportsScreenCaptureBlocking(),
        onOptionalBiometricsChange = controller::setOptionalBiometricsEnabled,
        onMultitaskMaskingChange = controller::setMultitaskMaskingEnabled,
        onScreenCaptureBlockingChange = controller::setScreenCaptureBlockingEnabled,
        onBack = onBack,
        modifier = modifier,
    )
}

/**
 * Hosts the settings entry point around the connection flow.
 *
 * Every platform shell composes `LocalAccessSettingsHost { ConnectionSetupScreen(...) }`,
 * so the navigation and the settings UI are shared Compose code, not duplicated
 * per platform. The connection screen is shown first (the app's current entry
 * point) with a "Settings" action; selecting it opens the local-access route,
 * whose back action returns to the connection content.
 *
 * [connectionContent] is supplied by the app shell because only the shell may
 * see both features.
 */
@Composable
public fun LocalAccessSettingsHost(
    modifier: Modifier = Modifier,
    connectionContent: @Composable () -> Unit,
) {
    var showSettings by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        if (showSettings) {
            LocalAccessSettingsRoute(onBack = { showSettings = false })
        } else {
            connectionContent()
            TextButton(
                onClick = { showSettings = true },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp),
            ) {
                Text(stringResource(Res.string.settings_open))
            }
        }
    }
}
