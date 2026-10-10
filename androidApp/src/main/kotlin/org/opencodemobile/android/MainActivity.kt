package org.opencodemobile.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.HorizontalDivider
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
import org.koin.mp.KoinPlatform
import org.opencodemobile.android.privacy.PrivacyShield
import org.opencodemobile.android.connection.ConnectionBinder
import org.opencodemobile.android.permission.PermissionHostActivity
import org.opencodemobile.design.system.LocalOpenCodeColors
import org.opencodemobile.design.system.OpenCodeContext
import org.opencodemobile.design.system.OpenCodeMetrics
import org.opencodemobile.design.system.OpenCodeSpacing
import org.opencodemobile.design.system.OpenCodeTheme
import org.opencodemobile.design.system.OpenCodeType
import org.opencodemobile.features.catalog.CatalogPresenter
import org.opencodemobile.features.catalog.ServerCatalogScreen
import org.opencodemobile.features.composer.ComposerBar
import org.opencodemobile.features.composer.ComposerPresenter
import org.opencodemobile.features.connection.AndroidQrCodeScanner
import org.opencodemobile.features.connection.ConnectionSetupController
import org.opencodemobile.features.connection.ConnectionSetupScreen
import org.opencodemobile.features.connection.QrCodeScanner
import org.opencodemobile.features.permissions.PermissionBanner
import org.opencodemobile.features.permissions.PermissionConfirmationScreen
import org.opencodemobile.features.permissions.PermissionDeepLink
import org.opencodemobile.features.permissions.PermissionsPresenter
import org.opencodemobile.features.questions.PendingQuestionsScreen
import org.opencodemobile.features.questions.QuestionsPresenter
import org.opencodemobile.features.sessions.SessionsPresenter
import org.opencodemobile.features.sessions.SessionsScreen
import org.opencodemobile.features.transcript.TranscriptPresenter
import org.opencodemobile.features.transcript.TranscriptScreen
import org.opencodemobile.features.settings.LocalAccessSettingsHost
import org.opencodemobile.shared.domain.localaccess.LocalAccessSettingsStore
import org.opencodemobile.shared.domain.session.SessionSummary

/**
 * The single Android host of the assembled MVP.
 *
 * It is a [FragmentActivity] because `BiometricPrompt` requires one; the
 * coordinator's biometric gate is bound to it through [PermissionHostActivity].
 *
 * It hosts two lots that landed on separate branches and are joined here:
 *
 * - L1 connection setup: the activity-scoped [AndroidQrCodeScanner] is created
 *   in [onCreate] (before the activity is `STARTED`, as
 *   `registerForActivityResult` requires) and passed to [ConnectionSetupScreen]
 *   together with the injected [ConnectionSetupController]. The connection
 *   composition root (`connectionCompositionModule`) provides the controller.
 * - L2/L3 surfaces (sessions, chat, permissions, questions, catalog): rendered
 *   by [PermissionHost] once their graph is wired.
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
@Suppress("TooManyFunctions")
class MainActivity : FragmentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* informational only */ }

    /**
     * A confirmation requested by a deep link, held until the request is actually
     * pending (a cold start loads the pending set asynchronously) and until the
     * activity is foregrounded (arming requires it).
     */
    private var requestedConfirmation by mutableStateOf<String?>(null)

    /**
     * The activity-scoped camera port. Created here from `onCreate` because
     * [AndroidQrCodeScanner] registers an `ActivityResultLauncher`.
     */
    private lateinit var qrCodeScanner: AndroidQrCodeScanner

    // §7.3: app-switcher cover + optional capture blocking on the activity lifecycle.
    private lateinit var privacyShield: PrivacyShield

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermission()
        consumePermissionIntent(intent)
        qrCodeScanner = AndroidQrCodeScanner(this)
        privacyShield = PrivacyShield(this, KoinPlatform.getKoin().get<LocalAccessSettingsStore>())
        val sessionsPresenter = sessionsPresenterOrNull()
        val transcriptPresenter = transcriptPresenterOrNull()
        val composerPresenter = composerPresenterOrNull()
        val questionsPresenter = questionsPresenterOrNull()
        val catalogPresenter = catalogPresenterOrNull()
        val connectionController = connectionSetupControllerOrNull()
        val connectionBinder = connectionBinderOrNull()
        enableEdgeToEdge()
        setContent {
            OpenCodeTheme(
                context = if (isSystemInDarkTheme()) OpenCodeContext.ChromeDark else OpenCodeContext.Chrome,
            ) {
                Surface(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                    // §7.3: the settings entry point is shared Compose code; the
                    // shell only supplies the connection/permission content.
                    LocalAccessSettingsHost(onScreenCaptureBlockingChanged = privacyShield::applyCapturePolicy) {
                        PermissionHost(
                            presenter = presenterOrNull(),
                            sessionsPresenter = sessionsPresenter,
                            transcriptPresenter = transcriptPresenter,
                            composerPresenter = composerPresenter,
                            questionsPresenter = questionsPresenter,
                            catalogPresenter = catalogPresenter,
                            connectionController = connectionController,
                            connectionBinder = connectionBinder,
                            scanner = qrCodeScanner,
                            requestedConfirmationId = requestedConfirmation,
                            onConfirmationRequestHandled = { requestedConfirmation = null },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumePermissionIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        privacyShield.onResume()
    }

    override fun onPause() {
        privacyShield.onPause()
        super.onPause()
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

    /**
     * The V1-07 question graph is only present once the connection composition
     * root is wired; until then the host renders the sessions list with no
     * question surface. Resolution is defensive so a missing dependency cannot
     * crash the app at launch.
     */
    private fun questionsPresenterOrNull(): QuestionsPresenter? =
        runCatching { GlobalContext.getOrNull()?.get<QuestionsPresenter>() }.getOrNull()

    /**
     * The V1-09 model/agent graph is only present once the connection
     * composition root binds the interaction gateway; until then the host
     * renders the sessions list with no catalog entry point. Resolution is
     * defensive so a missing dependency cannot crash the app at launch.
     */
    private fun catalogPresenterOrNull(): CatalogPresenter? =
        runCatching { GlobalContext.getOrNull()?.get<CatalogPresenter>() }.getOrNull()

    /**
     * L1: the connection setup graph (SecureStore-backed stores, identity gate,
     * [org.opencodemobile.shared.domain.connection.OpenCodeGateway],
     * [ConnectionSetupController]). It is registered by
     * `connectionCompositionModule`; resolution is defensive so a missing graph
     * cannot crash the app at launch.
     */
    private fun connectionSetupControllerOrNull(): ConnectionSetupController? =
        runCatching { GlobalContext.getOrNull()?.get<ConnectionSetupController>() }.getOrNull()

    /**
     * OPE-176: the live-connection holder. The home surface stays on the
     * connection screen until a handshake published a [LiveConnection], so an
     * unconnected app never renders an inert sessions list.
     */
    private fun connectionBinderOrNull(): ConnectionBinder? =
        runCatching { GlobalContext.getOrNull()?.get<ConnectionBinder>() }.getOrNull()
}

@Suppress("LongParameterList")
@Composable
private fun PermissionHost(
    presenter: PermissionsPresenter?,
    sessionsPresenter: SessionsPresenter?,
    transcriptPresenter: TranscriptPresenter?,
    composerPresenter: ComposerPresenter?,
    questionsPresenter: QuestionsPresenter?,
    catalogPresenter: CatalogPresenter?,
    connectionController: ConnectionSetupController?,
    connectionBinder: ConnectionBinder?,
    scanner: QrCodeScanner?,
    requestedConfirmationId: String?,
    onConfirmationRequestHandled: () -> Unit,
) {
    // OPE-176: the home surface is the connection screen until a handshake
    // published a live connection; the binder is observed so the switch to the
    // sessions list happens as soon as the graph is bound.
    val connected = connectionBinder?.live?.collectAsState()?.value != null
    if (presenter == null) {
        AppContent(
            sessionsPresenter,
            transcriptPresenter,
            composerPresenter,
            questionsPresenter,
            catalogPresenter,
            connectionController,
            connected,
            scanner,
        )
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
            AppContent(
                sessionsPresenter,
                transcriptPresenter,
                composerPresenter,
                questionsPresenter,
                catalogPresenter,
                connectionController,
                connected,
                scanner,
            )
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
 * The app's entry surfaces.
 *
 * When no session graph is wired yet, the connection setup screen (L1) is shown
 * if its controller is available: it is the only way to enter a server profile
 * and a QR payload. Once a session presenters exists (the L2/L3 graph is bound
 * by a connection composition root, OPE-176), the sessions list becomes the home
 * surface and a session can be opened.
 */
@Suppress("LongParameterList")
@Composable
private fun AppContent(
    sessionsPresenter: SessionsPresenter?,
    transcriptPresenter: TranscriptPresenter?,
    composerPresenter: ComposerPresenter?,
    questionsPresenter: QuestionsPresenter?,
    catalogPresenter: CatalogPresenter?,
    connectionController: ConnectionSetupController?,
    connected: Boolean,
    scanner: QrCodeScanner?,
) {
    var openSession by remember { mutableStateOf<SessionSummary?>(null) }
    var catalogOpen by remember { mutableStateOf(false) }
    val session = openSession
    if (session != null && transcriptPresenter != null && composerPresenter != null) {
        SessionContent(
            session = session,
            transcriptPresenter = transcriptPresenter,
            composerPresenter = composerPresenter,
            questionsPresenter = questionsPresenter,
            onBack = { openSession = null },
        )
        return
    }

    // V1-09: the models/agents the server exposes. Read-only, so it is reachable
    // whenever the catalog graph is wired, independently of a session.
    if (catalogOpen && catalogPresenter != null) {
        CatalogContent(presenter = catalogPresenter, onBack = { catalogOpen = false })
        return
    }

    // OPE-176: until a handshake binds the live graph, the home surface is the
    // L1 setup screen. The sessions list is only reachable once the L2/L3 seams
    // resolve a connection; otherwise it would render an inert, empty list.
    if (connectionController != null && !connected) {
        ConnectionSetupScreen(controller = connectionController, scanner = scanner)
        return
    }

    if (sessionsPresenter == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("OpenCode Mobile")
        }
        return
    }

    // V1-07: a question raised while no session is open must still be visible and
    // answerable. The banner is not dismissable; only reply/reject clear it.
    val questions = questionsPresenter?.state?.collectAsState()?.value
    Box(modifier = Modifier.fillMaxSize()) {
        SessionsScreen(
            presenter = sessionsPresenter,
            onOpenSession = { opened -> openSession = opened },
            onOpenCatalog = catalogPresenter?.let { { catalogOpen = true } },
        )
        if (questions != null && questions.hasPending && questionsPresenter != null) {
            QuestionBanner(
                questionsPresenter = questionsPresenter,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

/**
 * The V1-09 model/agent screen: it refreshes on entry and forwards the retry
 * intent. Every rule (server-is-source-of-truth, empty vs unsupported, transient
 * error) lives in the catalog controller/presenter; this only renders.
 */
@Composable
private fun CatalogContent(
    presenter: CatalogPresenter,
    onBack: () -> Unit,
) {
    val state by presenter.state.collectAsState()
    LaunchedEffect(Unit) { presenter.refresh() }
    Column(modifier = Modifier.fillMaxSize()) {
        TextButton(onClick = onBack) {
            Text("Back", style = OpenCodeType.control)
        }
        ServerCatalogScreen(
            state = state,
            onRetry = { presenter.refresh() },
            modifier = Modifier.weight(1f),
        )
    }
}

/** Renders the non-dismissable question banner and forwards reply/reject intents. */
@Composable
private fun QuestionBanner(
    questionsPresenter: QuestionsPresenter,
    modifier: Modifier = Modifier,
) {
    val state = questionsPresenter.state.collectAsState().value
    PendingQuestionsScreen(
        state = state,
        onReply = { requestId, answers -> questionsPresenter.answer(requestId, answers) },
        onReject = { requestId -> questionsPresenter.reject(requestId) },
        modifier = modifier,
    )
}

/**
 * The V1-05 session screen: the streamed transcript and the prompt composer.
 *
 * It renders both presenters and forwards intents; every gate (D2 reconciliation,
 * D8 offline, D9 single send, §8.1 draft) lives in the application controllers.
 * `docs/DESIGN-SYSTEM.md`: session context, monospace transcript, no bubbles.
 */
@Suppress("LongMethod")
@Composable
private fun SessionContent(
    session: SessionSummary,
    transcriptPresenter: TranscriptPresenter,
    composerPresenter: ComposerPresenter,
    questionsPresenter: QuestionsPresenter?,
    onBack: () -> Unit,
) {
    val transcript by transcriptPresenter.state.collectAsState()
    val composer by composerPresenter.state.collectAsState()
    val abortState by transcriptPresenter.abortState.collectAsState()
    val questions = questionsPresenter?.state?.collectAsState()?.value

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
                    abortState = abortState,
                    // V1-08: one explicit user action. The directory is bound
                    // once the connection composition root exposes the active
                    // project root; until then the server query stays unscoped.
                    onAbort = { transcriptPresenter.abort() },
                )
                // V1-07: a pending question is shown above the composer and is not
                // dismissable; only reply/reject clears it.
                if (questions != null && questions.hasPending && questionsPresenter != null) {
                    HorizontalDivider(color = colors.line, thickness = OpenCodeMetrics.hairline)
                    PendingQuestionsScreen(
                        state = questions,
                        onReply = { requestId, answers -> questionsPresenter.answer(requestId, answers) },
                        onReject = { requestId -> questionsPresenter.reject(requestId) },
                    )
                }
                HorizontalDivider(color = colors.line, thickness = OpenCodeMetrics.hairline)
                ComposerBar(
                    state = composer,
                    onDraftChange = composerPresenter::updateDraft,
                    onSend = composerPresenter::send,
                    // D8: the composer is disabled offline, not merely refused at send.
                    // V1-07: it is also disabled while a question blocks the turn.
                    enabled = !composer.offline && questions?.blocksTurn != true,
                )
            }
        }
    }
}
