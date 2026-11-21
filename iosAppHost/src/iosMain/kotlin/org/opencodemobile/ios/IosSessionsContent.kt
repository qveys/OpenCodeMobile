package org.opencodemobile.ios

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import org.jetbrains.compose.resources.stringResource
import org.opencodemobile.design.system.OpenCodeContext
import org.opencodemobile.design.system.OpenCodeTheme
import org.opencodemobile.design.system.resources.Res
import org.opencodemobile.design.system.resources.ui_back
import org.opencodemobile.features.composer.ComposerBar
import org.opencodemobile.features.permissions.PermissionBanner
import org.opencodemobile.features.permissions.PermissionConfirmationScreen
import org.opencodemobile.features.questions.PendingQuestionsScreen
import org.opencodemobile.features.settings.LocalAccessSettingsRoute
import org.opencodemobile.features.settings.localAccessStrings
import org.opencodemobile.shared.application.cache.toConnectionState
import org.opencodemobile.shared.domain.cache.ConnectionState
import org.opencodemobile.features.sessions.SessionsScreen
import org.opencodemobile.features.transcript.TranscriptScreen
import org.opencodemobile.shared.domain.session.SessionSummary
import kotlinx.coroutines.launch

/** Shared feature screens; the shell owns only navigation and lifecycle. */
@Composable
internal fun IosSessionsContent(runtime: IosSessionRuntime) {
    var openSession by remember(runtime) { mutableStateOf<SessionSummary?>(null) }
    var settingsOpen by remember(runtime) { mutableStateOf(false) }
    if (settingsOpen) {
        LocalAccessSettingsRoute(onBack = { settingsOpen = false })
        return
    }
    val permission by runtime.permissions.state.collectAsState()
    val questions by runtime.questions.state.collectAsState()
    val scope = rememberCoroutineScope()
    val banner = permission.banner
    Box(Modifier.fillMaxSize()) {
        if (permission.confirmationOpen && banner != null) {
            PermissionConfirmationScreen(
                model = banner,
                onDecision = { decision ->
                    scope.launch { runtime.permissions.approve(banner.requestId, decision, banner.contentFingerprint) }
                },
            )
        } else {
            Column(Modifier.fillMaxSize()) {
                TextButton(onClick = { settingsOpen = true }) { Text(localAccessStrings().open) }
                val session = openSession
                if (session == null) {
                    SessionsScreen(runtime.sessions, { openSession = it }, Modifier.weight(1f))
                } else {
                    IosSessionContent(runtime, session, { openSession = null }, Modifier.weight(1f))
                }
                if (questions.hasPending) {
                    PendingQuestionsScreen(
                        state = questions,
                        onReply = { sessionId, requestId, answer -> runtime.questions.answer(sessionId, requestId, answer) },
                        onReject = { sessionId, requestId -> runtime.questions.reject(sessionId, requestId) },
                    )
                }
            }
            if (banner != null) {
                PermissionBanner(
                    model = banner,
                    onApproveRequested = { runtime.permissions.openConfirmation(banner.requestId) },
                    onDeny = { scope.launch { runtime.permissions.deny(banner.requestId) } },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        }
    }
}

@Composable
private fun IosSessionContent(
    runtime: IosSessionRuntime,
    session: SessionSummary,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val connection by runtime.connection.collectAsState()
    val offline = connection.phase.toConnectionState() != ConnectionState.Online
    val transcript by runtime.transcript.state.collectAsState()
    val composer by runtime.composer.state.collectAsState()
    val abort by runtime.transcript.abortState.collectAsState()
    val questions by runtime.questions.state.collectAsState()
    LaunchedEffect(runtime, session.id) {
        runtime.transcript.open(session.id)
        runtime.composer.open(session.id)
    }
    DisposableEffect(runtime, session.id) {
        onDispose {
            runtime.transcript.close()
            runtime.composer.close()
        }
    }
    OpenCodeTheme(context = OpenCodeContext.Session) {
        Column(modifier) {
            TextButton(onClick = onBack) { Text(stringResource(Res.string.ui_back)) }
            Text(session.title)
            TranscriptScreen(
                state = transcript,
                modifier = Modifier.weight(1f),
                abortState = abort,
                onAbort = { runtime.transcript.abort() },
            )
            ComposerBar(
                state = composer.copy(offline = offline),
                onDraftChange = runtime.composer::updateDraft,
                onSend = runtime.composer::send,
                enabled = !offline && !questions.blocksTurn,
            )
        }
    }
}
