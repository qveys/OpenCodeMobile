package org.opencodemobile.features.connection

import android.app.Activity
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Android [QrCodeScanner] over the [QrScannerActivity] capture surface.
 *
 * The composition root constructs it from the host activity (it needs the
 * activity's `ActivityResultRegistry`) and passes it to
 * [ConnectionSetupScreen]. [scan] resolves with the decoded payload, or with
 * `null` when the user cancels or denies the camera permission, so the caller
 * can return to the entry screen without special-casing cancellation.
 *
 * It must be constructed from `onCreate`, before the activity reaches the
 * `STARTED` state, because `registerForActivityResult` requires it.
 */
public class AndroidQrCodeScanner(
    private val activity: ComponentActivity,
) : QrCodeScanner {

    private var pending: CancellableContinuation<String?>? = null

    private val launcher: ActivityResultLauncher<Intent> =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val continuation = pending ?: return@registerForActivityResult
            pending = null
            if (continuation.isActive) {
                val payload = if (result.resultCode == Activity.RESULT_OK) {
                    result.data?.getStringExtra(QrScannerActivity.EXTRA_PAYLOAD)
                } else {
                    null
                }
                continuation.resume(payload)
            }
        }

    override suspend fun scan(): String? = suspendCancellableCoroutine { continuation ->
        pending?.cancel()
        pending = continuation
        continuation.invokeOnCancellation { if (pending === continuation) pending = null }
        launcher.launch(QrScannerActivity.intent(activity))
    }
}
