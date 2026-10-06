package org.opencodemobile.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import org.koin.compose.koinInject
import org.opencodemobile.features.connection.AndroidQrCodeScanner
import org.opencodemobile.features.connection.ConnectionSetupController
import org.opencodemobile.features.connection.ConnectionSetupScreen

/**
 * Android host. It wires the platform camera adapter into the connection
 * feature: the activity-scoped [AndroidQrCodeScanner] (the
 * [org.opencodemobile.features.connection.QrCodeScanner] implementation) is
 * created here — before the activity is `STARTED`, as
 * `registerForActivityResult` requires — and passed to
 * [ConnectionSetupScreen] together with the injected controller.
 *
 * V1-13: it also asks for `POST_NOTIFICATIONS` on API 33+, so the local
 * notification surface is actually allowed to show. A denied permission is not
 * an error — the notification is a best-effort signal and the app loses no state.
 *
 * The Koin graph behind the controller lives in [connectionCompositionModule].
 */
class MainActivity : ComponentActivity() {

    private lateinit var qrCodeScanner: AndroidQrCodeScanner

    // Registered before onStart, as the Activity Result API requires. The result
    // is intentionally ignored: whether the user allows or denies notifications
    // does not change the app state (V1-13 criterion 4).
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        qrCodeScanner = AndroidQrCodeScanner(this)
        requestNotificationPermissionIfNeeded()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val controller: ConnectionSetupController = koinInject()
                    ConnectionSetupScreen(controller = controller, scanner = qrCodeScanner)
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
