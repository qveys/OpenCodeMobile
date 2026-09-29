package org.opencodemobile.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import org.opencodemobile.android.permission.PermissionHostActivity
import org.opencodemobile.features.permissions.PermissionBanner
import org.opencodemobile.features.permissions.PermissionConfirmationScreen
import org.opencodemobile.features.permissions.PermissionDeepLink
import org.opencodemobile.features.permissions.PermissionsPresenter
import org.opencodemobile.features.sessions.SessionsPresenter
import org.opencodemobile.features.sessions.SessionsScreen

/**
 * The Android host for the permission surface (OPE-173 / V1-06).
 *
 * It is a [FragmentActivity] because `BiometricPrompt` requires one; the
 * coordinator's biometric gate is bound to it through [PermissionHostActivity].
 *
 * Responsibilities:
 * - render [PermissionBanner] while a request is pending and
 *   [PermissionConfirmationScreen] while the confirmation is armed,
 * - forward the safe direction (Deny) directly and route every approval through the
 *   confirmation screen (`openConfirmation`), which runs the foreground + biometric
 *   gates in the coordinator,
 * - report the foreground lifecycle so a backgrounded confirmation is discarded,
 * - resolve `opencodemobile://permission/confirmation/{id}` to the confirmation
 *   screen only — a deep link never carries or applies a decision.
 */
class MainActivity : FragmentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* informational only */ }

    /**
     * A confirmation requested by a deep link, held until the request is actually
     * pending (a cold start loads the pending set asynchronously) and until the
     * activity is foregrounded (arming requires it).
     */
    private var requestedConfirmation by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermission()
        consumePermissionIntent(intent)
        val sessionsPresenter = sessionsPresenterOrNull()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PermissionHost(
                        presenter = presenterOrNull(),
                        sessionsPresenter = sessionsPresenter,
                        requestedConfirmationId = requestedConfirmation,
                        onConfirmationRequestHandled = { requestedConfirmation = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumePermissionIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        PermissionHostActivity.current = this
        presenterOrNull()?.onForegroundChanged(true)
    }

    override fun onStop() {
        PermissionHostActivity.current = null
        presenterOrNull()?.onForegroundChanged(false)
        super.onStop()
    }

    /**
     * A notification tap arrives as an `ACTION_VIEW` intent on
     * `opencodemobile://permission/confirmation/{id}`. Only the request id is read;
     * the parser rejects any link that tries to carry a decision.
     */
    private fun consumePermissionIntent(intent: Intent?) {
        PermissionDeepLink.confirmationRequestId(intent?.dataString)?.let {
            requestedConfirmation = it
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val permission = Manifest.permission.POST_NOTIFICATIONS
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            return
        }
        notificationPermission.launch(permission)
    }

    /**
     * The permission graph is only present once the connection/cache composition
     * roots are wired; until then the host renders the placeholder shell. Resolution
     * is defensive so a missing dependency cannot crash the app at launch.
     */
    private fun presenterOrNull(): PermissionsPresenter? =
        runCatching { GlobalContext.getOrNull()?.get<PermissionsPresenter>() }.getOrNull()

    /**
     * The sessions graph is only present once the cache/connection composition
     * roots are wired; until then the host renders the placeholder shell. It is
     * the V1-04 home surface: it lists, creates, opens, renames, deletes and
     * forks sessions through the application controller.
     */
    private fun sessionsPresenterOrNull(): SessionsPresenter? =
        runCatching { GlobalContext.getOrNull()?.get<SessionsPresenter>() }.getOrNull()
}

@Composable
private fun PermissionHost(
    presenter: PermissionsPresenter?,
    sessionsPresenter: SessionsPresenter?,
    requestedConfirmationId: String?,
    onConfirmationRequestHandled: () -> Unit,
) {
    if (presenter == null) {
        HomeContent(sessionsPresenter)
        return
    }
    val state = presenter.state.collectAsState().value
    val scope = rememberCoroutineScope()
    val banner = state.banner

    // A deep link only opens the confirmation screen, and only for a request that is
    // actually pending. It never submits anything.
    LaunchedEffect(banner?.requestId, requestedConfirmationId) {
        if (requestedConfirmationId != null && banner?.requestId == requestedConfirmationId) {
            presenter.openConfirmation(requestedConfirmationId)
            onConfirmationRequestHandled()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (state.confirmationOpen && banner != null) {
            PermissionConfirmationScreen(
                model = banner,
                onDecision = { decision ->
                    scope.launch {
                        presenter.approve(banner.requestId, decision, banner.contentFingerprint)
                    }
                },
            )
        } else {
            HomeContent(sessionsPresenter)
            if (banner != null) {
                PermissionBanner(
                    model = banner,
                    onApproveRequested = { presenter.openConfirmation(banner.requestId) },
                    onDeny = { scope.launch { presenter.deny(banner.requestId) } },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        }
    }
}

/**
 * The V1-04 home surface: the sessions list. Until the connection/onboarding
 * composition root lands, an unwired graph renders the placeholder shell.
 *
 * Opening a session navigates to the session/transcript screen, which lands with
 * OPE-109 (V1-05); the list itself is fully wired.
 */
@Composable
private fun HomeContent(sessionsPresenter: SessionsPresenter?) {
    if (sessionsPresenter == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("OpenCode Mobile")
        }
        return
    }
    SessionsScreen(
        presenter = sessionsPresenter,
        onOpenSession = { /* The session screen lands with OPE-109 (V1-05). */ },
    )
}
