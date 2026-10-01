package org.opencodemobile.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
 * The Koin graph behind the controller lives in [connectionCompositionModule].
 */
class MainActivity : ComponentActivity() {

    private lateinit var qrCodeScanner: AndroidQrCodeScanner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        qrCodeScanner = AndroidQrCodeScanner(this)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val controller: ConnectionSetupController = koinInject()
                    ConnectionSetupScreen(controller = controller, scanner = qrCodeScanner)
                }
            }
        }
    }
}
