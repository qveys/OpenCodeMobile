package org.opencodemobile.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import org.koin.compose.koinInject
import org.koin.mp.KoinPlatform
import org.opencodemobile.android.privacy.PrivacyShield
import org.opencodemobile.features.connection.AndroidQrCodeScanner
import org.opencodemobile.features.connection.ConnectionSetupController
import org.opencodemobile.features.connection.ConnectionSetupScreen
import org.opencodemobile.shared.domain.localaccess.LocalAccessSettingsStore

/**
 * Android host. It wires the platform camera adapter into the connection
 * feature: the activity-scoped [AndroidQrCodeScanner] (the
 * [org.opencodemobile.features.connection.QrCodeScanner] implementation) is
 * created here — before the activity is `STARTED`, as
 * `registerForActivityResult` requires — and passed to
 * [ConnectionSetupScreen] together with the injected controller.
 *
 * §7.3: it also applies the device-local access protections. The app-switcher
 * cover is set in `onPause` and removed in `onResume`; capture blocking is only
 * applied when the user enabled it.
 *
 * The Koin graph behind the controller lives in [connectionCompositionModule].
 */
class MainActivity : ComponentActivity() {

    private lateinit var qrCodeScanner: AndroidQrCodeScanner
    private lateinit var privacyShield: PrivacyShield

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        qrCodeScanner = AndroidQrCodeScanner(this)
        // §7.3: the app-switcher cover and the optional capture blocking are
        // applied on the activity lifecycle, so the shield is built here with the
        // device-local settings store Koin bound in [localAccessCompositionModule].
        privacyShield = PrivacyShield(this, KoinPlatform.getKoin().get<LocalAccessSettingsStore>())

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val controller: ConnectionSetupController = koinInject()
                    ConnectionSetupScreen(controller = controller, scanner = qrCodeScanner)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Removes the app-switcher cover and applies the capture policy before
        // the content is visible again.
        privacyShield.onResume()
    }

    override fun onPause() {
        // Covers the content before the OS snapshots the activity for the app
        // switcher (masking on by default; this never sets FLAG_SECURE).
        privacyShield.onPause()
        super.onPause()
    }
}
