package org.opencodemobile.features.connection

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch

/**
 * Host composable for the connection setup flow: manual entry, QR capture, and
 * the shared import review screen.
 *
 * It is the only place that knows about the [QrCodeScanner] port; when [scanner]
 * is null the flow still works through manual entry and deep links.
 */
@Composable
public fun ConnectionSetupScreen(
    controller: ConnectionSetupController,
    scanner: QrCodeScanner? = null,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()

    ConnectionSetupContent(
        state = state,
        onAddressChange = controller::onAddressChange,
        onLabelChange = controller::onLabelChange,
        onSubmit = controller::submitManualEntry,
        onScanRequest = scanner?.let { codeScanner ->
            {
                scope.launch {
                    codeScanner.scan()?.let(controller::submitScannedPayload)
                }
            }
        },
        onConfirm = controller::confirmReview,
        onConfirmIdentity = controller::confirmIdentity,
        onCancel = controller::cancelReview,
        modifier = modifier,
    )
}

/** Stateless split between the entry screen and the import review screen. */
@Composable
public fun ConnectionSetupContent(
    state: ConnectionSetupUiState,
    onAddressChange: (String) -> Unit,
    onLabelChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onScanRequest: (() -> Unit)?,
    onConfirm: () -> Unit,
    onConfirmIdentity: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.isReviewing) {
        ServerImportReviewScreen(
            state = state,
            onConfirm = onConfirm,
            onConfirmIdentity = onConfirmIdentity,
            onCancel = onCancel,
            modifier = modifier,
        )
    } else {
        ManualServerEntryScreen(
            state = state,
            onAddressChange = onAddressChange,
            onLabelChange = onLabelChange,
            onSubmit = onSubmit,
            onScanRequest = onScanRequest,
            modifier = modifier,
        )
    }
}
