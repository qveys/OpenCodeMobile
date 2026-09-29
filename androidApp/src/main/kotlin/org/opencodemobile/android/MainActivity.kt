package org.opencodemobile.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import org.opencodemobile.android.permission.PermissionHostActivity
import org.opencodemobile.design.system.LocalOpenCodeColors
import org.opencodemobile.design.system.OpenCodeContext
import org.opencodemobile.design.system.OpenCodeMetrics
import org.opencodemobile.design.system.OpenCodeSpacing
import org.opencodemobile.design.system.OpenCodeTheme
import org.opencodemobile.design.system.OpenCodeType
import org.opencodemobile.features.composer.ComposerBar
import org.opencodemobile.features.composer.ComposerPresenter
import org.opencodemobile.features.permissions.PermissionBanner
import org.opencodemobile.features.permissions.PermissionConfirmationScreen
import org.opencodemobile.features.permissions.PermissionDeepLink
import org.opencodemobile.features.permissions.PermissionsPresenter
import org.opencodemobile.features.sessions.SessionsPresenter
import org.opencodemobile.features.sessions.SessionsScreen
import org.opencodemobile.features.transcript.TranscriptPresenter
import org.opencodemobile.features.transcript.TranscriptScreen
import org.opencodemobile.shared.domain.session.SessionSummary

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
        val transcriptPresenter = transcriptPresenterOrNull()
        val composerPresenter = composerPresenterOrNull()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PermissionHost(
                        presenter = presenterOrNull(),
                        sessionsPresenter = sessionsPresenter,
                        transcriptPresenter = transcriptPresenter,
                        composerPresenter = composerPresenter,
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

    /**
     * The V1-05 chat graph is only present once the connection composition root
     * is wired; until then the host renders the sessions list and never opens a
     * session screen. Resolution is defensive so a missing dependency cannot
     * crash the app at launch.
     */
    private fun transcriptPresenterOrNull(): TranscriptPresenter? =
        runCatching { GlobalContext.getOrNull()?.get<TranscriptPresenter>() }.getOrNull()

    private fun composerPresenterOrNull(): ComposerPresenter? =
        runCatching { GlobalContext.getOrNull()?.get<ComposerPresenter>() }.getOrNull()
}

@Composable
private fun PermissionHost(
    presenter: PermissionsPresenter?,
    sessionsPresenter: SessionsPresenter?,
    transcriptPresenter: TranscriptPresenter?,
    composerPresenter: ComposerPresenter?,
    requestedConfirmationId: String?,
    onConfirmationRequestHandled: () -> Unit,
) {
    if (presenter == null) {
        AppContent(sessionsPresenter, transcriptPresenter, composerPresenter)
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
            AppContent(sessionsPresenter, transcriptPresenter, composerPresenter)
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
 * The app's two surfaces: the V1-04 sessions list, and the V1-05 session screen
 * (transcript + composer) it navigates to.
 *
 * Until the connection/onboarding composition root lands, an unwired chat graph
 * falls back to the sessions list and a session cannot be opened.
 */
@Composable
private fun AppContent(
    sessionsPresenter: SessionsPresenter?,
    transcriptPresenter: TranscriptPresenter?,
    composerPresenter: ComposerPresenter?,
) {
    var openSession by remember { mutableStateOf<SessionSummary?>(null) }
    val session = openSession
    if (session != null && transcriptPresenter != null && composerPresenter != null) {
        SessionContent(
            session = session,
            transcriptPresenter = transcriptPresenter,
            composerPresenter = composerPresenter,
            onBack = { openSession = null },
        )
        return
    }

    if (sessionsPresenter == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("OpenCode Mobile")
        }
        return
    }
    SessionsScreen(
        presenter = sessionsPresenter,
        onOpenSession = { opened -> openSession = opened },
    )
}

/**
 * The V1-05 session screen: the streamed transcript and the prompt composer.
 *
 * It renders both presenters and forwards intents; every gate (D2 reconciliation,
 * D8 offline, D9 single send, §8.1 draft) lives in the application controllers.
 * `docs/DESIGN-SYSTEM.md`: session context, monospace transcript, no bubbles.
 */
@Composable
private fun SessionContent(
    session: SessionSummary,
    transcriptPresenter: TranscriptPresenter,
    composerPresenter: ComposerPresenter,
    onBack: () -> Unit,
) {
    val transcript by transcriptPresenter.state.collectAsState()
    val composer by composerPresenter.state.collectAsState()

    LaunchedEffect(session.id) {
        transcriptPresenter.open(session.id)
        composerPresenter.open(session.id)
    }
    DisposableEffect(session.id) {
        onDispose {
            transcriptPresenter.close()
            composerPresenter.close()
        }
    }

    OpenCodeTheme(context = OpenCodeContext.Session) {
        val colors = LocalOpenCodeColors.current
        Surface(modifier = Modifier.fillMaxSize(), color = colors.bg, contentColor = colors.text) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = OpenCodeSpacing.x3, vertical = OpenCodeSpacing.x2),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onBack) {
                        Text("Back", style = OpenCodeType.control)
                    }
                    Text(
                        text = session.title,
                        style = OpenCodeType.section,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                HorizontalDivider(color = colors.line, thickness = OpenCodeMetrics.hairline)
                TranscriptScreen(
                    state = transcript,
                    modifier = Modifier.weight(1f),
                )
                HorizontalDivider(color = colors.line, thickness = OpenCodeMetrics.hairline)
                ComposerBar(
                    state = composer,
                    onDraftChange = composerPresenter::updateDraft,
                    onSend = composerPresenter::send,
                    // D8: the composer is disabled offline, not merely refused at send.
                    enabled = !composer.offline,
                )
            }
        }
    }
}
