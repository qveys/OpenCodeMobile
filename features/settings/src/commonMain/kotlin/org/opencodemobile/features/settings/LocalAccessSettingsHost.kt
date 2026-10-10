package org.opencodemobile.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import org.opencodemobile.design.system.OpenCodeSpace
import org.opencodemobile.design.system.OpenCodeType
import org.jetbrains.compose.resources.stringResource
import org.opencodemobile.design.system.resources.Res
import org.opencodemobile.design.system.resources.connection_manual_title
import org.koin.compose.koinInject

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
    onScreenCaptureBlockingChanged: () -> Unit = {},
    modifier: Modifier = Modifier,
    strings: LocalAccessStrings = localAccessStrings(),
) {
    val controller: LocalAccessSettingsController = koinInject()
    val state by controller.state.collectAsState()

    LocalAccessSettingsScreen(
        state = state,
        actions = LocalAccessSettingsActions(
            onOptionalBiometricsChange = controller::setOptionalBiometricsEnabled,
            onMultitaskMaskingChange = controller::setMultitaskMaskingEnabled,
            onScreenCaptureBlockingChange = {
                controller.setScreenCaptureBlockingEnabled(it)
                onScreenCaptureBlockingChanged()
            },
        ),
        strings = strings,
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
 * [onScreenCaptureBlockingChanged] fires after the preference is saved so the
 * platform shell can apply it at once instead of on its next resume.
 *
 * [connectionContent] is supplied by the app shell because only the shell may
 * see both features.
 */
@Composable
public fun LocalAccessSettingsHost(
    modifier: Modifier = Modifier,
    onScreenCaptureBlockingChanged: () -> Unit = {},
    strings: LocalAccessStrings = localAccessStrings(),
    connectionContent: @Composable () -> Unit,
) {
    var showSettings by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        if (showSettings) {
            LocalAccessSettingsRoute(
                onBack = { showSettings = false },
                onScreenCaptureBlockingChanged = onScreenCaptureBlockingChanged,
                strings = strings,
            )
        } else {
            val title = stringResource(Res.string.connection_manual_title)
            val titleStyle = OpenCodeType.title
            val settingsAction: @Composable () -> Unit = {
                TextButton(
                    onClick = { showSettings = true },
                    modifier = Modifier.heightIn(min = OpenCodeSpace.hitAndroid),
                ) { Text(strings.open, style = OpenCodeType.control) }
            }
            Column(modifier = Modifier.fillMaxSize()) {
                if (LocalDensity.current.fontScale >= 1.3f) {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = OpenCodeSpace.space4), verticalArrangement = Arrangement.spacedBy(OpenCodeSpace.space2)) {
                        Text(title, style = titleStyle, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        settingsAction()
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = OpenCodeSpace.space4),
                        horizontalArrangement = Arrangement.spacedBy(OpenCodeSpace.space3),
                    ) {
                        Text(
                            text = title,
                            modifier = Modifier.weight(1f).padding(vertical = OpenCodeSpace.space2),
                            style = titleStyle,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        settingsAction()
                    }
                }
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    connectionContent()
                }
            }
        }
    }
}
