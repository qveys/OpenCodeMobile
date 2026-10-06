package org.opencodemobile.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.opencodemobile.design.system.resources.Res
import org.opencodemobile.design.system.resources.settings_erase_category_cache
import org.opencodemobile.design.system.resources.settings_erase_category_credentials
import org.opencodemobile.design.system.resources.settings_erase_category_identity_pin
import org.opencodemobile.design.system.resources.settings_erase_category_notifications
import org.opencodemobile.design.system.resources.settings_erase_category_profile
import org.opencodemobile.design.system.resources.settings_erase_category_session
import org.opencodemobile.design.system.resources.settings_erase_everything_action
import org.opencodemobile.design.system.resources.settings_erase_everything_cancel
import org.opencodemobile.design.system.resources.settings_erase_everything_confirm
import org.opencodemobile.design.system.resources.settings_erase_everything_confirm_body
import org.opencodemobile.design.system.resources.settings_erase_everything_confirm_title
import org.opencodemobile.design.system.resources.settings_erase_everything_description
import org.opencodemobile.design.system.resources.settings_erase_everything_done
import org.opencodemobile.design.system.resources.settings_erase_everything_failed
import org.opencodemobile.design.system.resources.settings_erase_everything_failed_categories
import org.opencodemobile.design.system.resources.settings_erase_everything_in_progress
import org.opencodemobile.design.system.resources.settings_erase_everything_reauth_failed
import org.opencodemobile.design.system.resources.settings_erase_everything_title
import org.opencodemobile.shared.application.erasure.ErasedCategory

/**
 * The "Tout effacer" surface (ADR 0009).
 *
 * It renders the current [EraseEverythingUiState] and forwards user intent to
 * the presenter. The confirmation dialog states **exactly** what will be erased
 * before the action can run; after completion the screen reports the result. It
 * holds no state itself and never touches a platform API, so Android and iOS
 * render the same screen.
 */
@Composable
public fun EraseEverythingScreen(
    controller: EraseEverythingController,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsState()
    EraseEverythingScreen(
        state = state,
        actions = EraseEverythingActions(
            requestErase = controller::requestErase,
            confirmErase = controller::confirmErase,
            cancelErase = controller::cancelErase,
            dismiss = controller::dismiss,
        ),
        modifier = modifier,
    )
}

/**
 * Stateless form, kept separate so the same rendering can be driven directly in
 * tests and previews without a live coordinator.
 */
@Composable
public fun EraseEverythingScreen(
    state: EraseEverythingUiState,
    actions: EraseEverythingActions,
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(Res.string.settings_erase_everything_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = stringResource(Res.string.settings_erase_everything_description),
                style = MaterialTheme.typography.bodyMedium,
            )
            when (state) {
                EraseEverythingUiState.Idle -> Button(
                    onClick = actions.requestErase,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(Res.string.settings_erase_everything_action))
                }

                EraseEverythingUiState.Confirming -> Unit

                EraseEverythingUiState.Erasing -> Text(
                    text = stringResource(Res.string.settings_erase_everything_in_progress),
                    style = MaterialTheme.typography.bodyMedium,
                )

                is EraseEverythingUiState.Erased -> ResultMessage(
                    text = stringResource(Res.string.settings_erase_everything_done),
                    onDismiss = actions.dismiss,
                )

                is EraseEverythingUiState.Failed -> FailureMessage(
                    state = state,
                    onDismiss = actions.dismiss,
                )

                EraseEverythingUiState.ReauthenticationFailed -> ResultMessage(
                    text = stringResource(Res.string.settings_erase_everything_reauth_failed),
                    onDismiss = actions.dismiss,
                )
            }
        }
    }

    if (state is EraseEverythingUiState.Confirming) {
        EraseEverythingConfirmDialog(
            onConfirm = actions.confirmErase,
            onCancel = actions.cancelErase,
        )
    }
}

@Composable
private fun EraseEverythingConfirmDialog(
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(Res.string.settings_erase_everything_confirm_title)) },
        text = { Text(stringResource(Res.string.settings_erase_everything_confirm_body)) },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(Res.string.settings_erase_everything_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(Res.string.settings_erase_everything_cancel))
            }
        },
    )
}

@Composable
private fun ResultMessage(text: String, onDismiss: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onDismiss) {
            Text(stringResource(Res.string.settings_erase_everything_cancel))
        }
    }
}

/**
 * Reports what is still on the device after a partial erase (ADR 0009 §2.2:
 * "after completion, it reports what was erased"). The failed categories come
 * from the coordinator, so a credential residue (F2) is distinguishable from a
 * failed notification clear.
 */
@Composable
private fun FailureMessage(state: EraseEverythingUiState.Failed, onDismiss: () -> Unit) {
    val failed = state.report?.failures?.map { it.category }?.distinct().orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(Res.string.settings_erase_everything_failed),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (failed.isNotEmpty()) {
            Text(
                text = stringResource(Res.string.settings_erase_everything_failed_categories),
                style = MaterialTheme.typography.bodyMedium,
            )
            failed.forEach { category ->
                Text(
                    text = "• ${eraseCategoryLabel(category)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        TextButton(onClick = onDismiss) {
            Text(stringResource(Res.string.settings_erase_everything_cancel))
        }
    }
}

@Composable
private fun eraseCategoryLabel(category: ErasedCategory): String = stringResource(
    when (category) {
        ErasedCategory.Session -> Res.string.settings_erase_category_session
        ErasedCategory.Credentials -> Res.string.settings_erase_category_credentials
        ErasedCategory.Profile -> Res.string.settings_erase_category_profile
        ErasedCategory.IdentityPin -> Res.string.settings_erase_category_identity_pin
        ErasedCategory.Cache -> Res.string.settings_erase_category_cache
        ErasedCategory.Notifications -> Res.string.settings_erase_category_notifications
    },
)
