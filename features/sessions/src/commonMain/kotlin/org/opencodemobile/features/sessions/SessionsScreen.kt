package org.opencodemobile.features.sessions

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.opencodemobile.design.system.LocalOpenCodeColors
import org.opencodemobile.design.system.OpenCodeContext
import org.opencodemobile.design.system.OpenCodeMetrics
import org.opencodemobile.design.system.OpenCodeSpacing
import org.opencodemobile.design.system.OpenCodeTheme
import org.opencodemobile.design.system.OpenCodeType
import org.opencodemobile.design.system.resources.Res
import org.opencodemobile.design.system.resources.ui_more_actions
import org.opencodemobile.design.system.SessionRow
import org.opencodemobile.design.system.SessionRowStatus
import org.opencodemobile.design.system.resources.session_action_delete
import org.opencodemobile.design.system.resources.session_action_fork
import org.opencodemobile.design.system.resources.session_action_open
import org.opencodemobile.design.system.resources.session_action_rename
import org.opencodemobile.design.system.resources.session_cancel
import org.opencodemobile.design.system.resources.session_count_live
import org.opencodemobile.design.system.resources.session_delete_body
import org.opencodemobile.design.system.resources.session_delete_title
import org.opencodemobile.design.system.resources.session_empty
import org.opencodemobile.design.system.resources.session_empty_hint
import org.opencodemobile.design.system.resources.session_empty_offline_hint
import org.opencodemobile.design.system.resources.session_error_not_found
import org.opencodemobile.design.system.resources.session_error_offline
import org.opencodemobile.design.system.resources.session_error_unreachable
import org.opencodemobile.design.system.resources.session_list_failed
import org.opencodemobile.design.system.resources.session_loading
import org.opencodemobile.design.system.resources.session_models_agents
import org.opencodemobile.design.system.resources.session_new
import org.opencodemobile.design.system.resources.session_offline_banner
import org.opencodemobile.design.system.resources.session_rename_title
import org.opencodemobile.design.system.resources.session_retry
import org.opencodemobile.design.system.resources.session_title
import org.opencodemobile.design.system.resources.session_title_invalid
import org.opencodemobile.design.system.resources.session_title_label
import org.opencodemobile.shared.application.session.SessionListError
import org.opencodemobile.shared.application.session.SessionListState
import org.opencodemobile.shared.domain.session.SessionSummary
import org.opencodemobile.shared.domain.session.SessionTitlePolicy

/**
 * The V1-04 sessions list screen.
 *
 * It presents the application state and forwards intents; it makes no network
 * or cache call of its own. The offline read-only state, the server capability
 * (fork) and the error copy come from the controller, so this screen never
 * decides a capability locally.
 *
 * `docs/DESIGN-SYSTEM.md`: chrome context, monospace, no cards, no avatars,
 * explicit technical states.
 */
@Composable
public fun SessionsScreen(
    presenter: SessionsPresenter,
    onOpenSession: (SessionSummary) -> Unit,
    modifier: Modifier = Modifier,
    onOpenCatalog: (() -> Unit)? = null,
) {
    OpenCodeTheme(
        context = if (isSystemInDarkTheme()) OpenCodeContext.ChromeDark else OpenCodeContext.Chrome,
    ) {
        val state by presenter.state.collectAsState()
        val colors = LocalOpenCodeColors.current
        val scope = rememberCoroutineScope()

        var renameTarget by remember { mutableStateOf<SessionSummary?>(null) }
        var deleteTarget by remember { mutableStateOf<SessionSummary?>(null) }

        LaunchedEffect(Unit) { presenter.refresh() }

        Surface(
            modifier = modifier.fillMaxSize(),
            color = colors.bg,
            contentColor = colors.text,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                SessionsTopBar(
                    offline = state.offline,
                    onNewSession = { presenter.createSession() },
                    onOpenCatalog = onOpenCatalog,
                )
                TechnicalStatus(state = state, onRetry = { presenter.refresh() })
                HorizontalDivider(color = colors.line, thickness = OpenCodeMetrics.hairline)
                SessionsBody(
                    state = state,
                    onOpen = { session ->
                        scope.launch { presenter.openSession(session.id)?.let(onOpenSession) }
                    },
                    onRename = { renameTarget = it },
                    onFork = { presenter.forkSession(it.id) },
                    onDelete = { deleteTarget = it },
                    onNewSession = { presenter.createSession() },
                )
            }
        }

        renameTarget?.let { target ->
            RenameDialog(
                session = target,
                onDismiss = { renameTarget = null },
                onConfirm = { title ->
                    presenter.renameSession(target.id, title)
                    renameTarget = null
                },
            )
        }

        deleteTarget?.let { target ->
            DeleteDialog(
                session = target,
                onDismiss = { deleteTarget = null },
                onConfirm = {
                    presenter.deleteSession(target.id)
                    deleteTarget = null
                },
            )
        }
    }
}

@Composable
private fun SessionsTopBar(
    offline: Boolean,
    onNewSession: () -> Unit,
    onOpenCatalog: (() -> Unit)?,
) {
    val colors = LocalOpenCodeColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = OpenCodeSpacing.x4, vertical = OpenCodeSpacing.x3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.session_title),
            style = OpenCodeType.title,
            modifier = Modifier.weight(1f),
        )
        // V1-09: the models/agents the server exposes, reachable from the home
        // surface. Read-only, so it stays available offline.
        if (onOpenCatalog != null) {
            TextButton(onClick = onOpenCatalog) {
                Text(stringResource(Res.string.session_models_agents), style = OpenCodeType.control)
            }
        }
        // D8: the mutation affordance is disabled offline, not merely refused.
        Button(
            onClick = onNewSession,
            enabled = !offline,
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.primary,
                contentColor = colors.onPrimary,
            ),
        ) {
            Text(stringResource(Res.string.session_new), style = OpenCodeType.control)
        }
    }
}

/**
 * The explicit technical state line: offline, the last error, or the last
 * successful mutation. A blank screen is never an acceptable failure mode.
 */
@Composable
private fun TechnicalStatus(state: SessionListState, onRetry: () -> Unit) {
    val colors = LocalOpenCodeColors.current
    val message: String? = when {
        state.offline -> stringResource(Res.string.session_offline_banner)
        state.error != null -> state.error!!.message()
        else -> state.notice
    }
    if (message == null) {
        if (!state.loading && state.sessions.isNotEmpty()) {
            Text(
                text = stringResource(Res.string.session_count_live, state.sessions.size),
                style = OpenCodeType.meta,
                color = colors.textMuted,
                modifier = Modifier.padding(horizontal = OpenCodeSpacing.x4, vertical = OpenCodeSpacing.x2),
            )
        }
        return
    }

    val isError = state.error != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = OpenCodeSpacing.x4, vertical = OpenCodeSpacing.x2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = OpenCodeType.tech,
            color = if (isError) colors.danger else colors.textMuted,
            modifier = Modifier.weight(1f),
        )
        if (isError) {
            TextButton(onClick = onRetry) {
                Text(stringResource(Res.string.session_retry), style = OpenCodeType.control)
            }
        }
    }
}

// Detekt: one composable renders all branches of the list (loading, empty,
// rows); the explicit state machine is clearer inline than split further.
@Suppress("LongParameterList", "LongMethod")
@Composable
private fun SessionsBody(
    state: SessionListState,
    onOpen: (SessionSummary) -> Unit,
    onRename: (SessionSummary) -> Unit,
    onFork: (SessionSummary) -> Unit,
    onDelete: (SessionSummary) -> Unit,
    onNewSession: () -> Unit,
) {
    val colors = LocalOpenCodeColors.current

    when {
        state.loading && state.sessions.isEmpty() -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(Res.string.session_loading), style = OpenCodeType.tech, color = colors.textMuted)
        }

        state.sessions.isEmpty() -> Column(
            modifier = Modifier.fillMaxSize().padding(OpenCodeSpacing.x4),
            verticalArrangement = Arrangement.spacedBy(OpenCodeSpacing.x3),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = if (state.error != null) {
                    stringResource(Res.string.session_list_failed)
                } else {
                    stringResource(Res.string.session_empty)
                },
                style = OpenCodeType.section,
            )
            Text(
                text = if (state.offline) {
                    stringResource(Res.string.session_empty_offline_hint)
                } else {
                    stringResource(Res.string.session_empty_hint)
                },
                style = OpenCodeType.body,
                color = colors.textBody,
            )
            if (!state.offline) {
                Button(
                    onClick = onNewSession,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primary,
                        contentColor = colors.onPrimary,
                    ),
                ) {
                    Text(stringResource(Res.string.session_new), style = OpenCodeType.control)
                }
            }
        }

        else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(state.sessions, key = { it.id }) { session ->
                SessionItem(
                    session = session,
                    forkAvailable = state.forkAvailable,
                    busy = state.pendingSessionId == session.id,
                    mutationsEnabled = !state.offline,
                    onOpen = { onOpen(session) },
                    onRename = { onRename(session) },
                    onFork = { onFork(session) },
                    onDelete = { onDelete(session) },
                )
                HorizontalDivider(color = colors.line, thickness = OpenCodeMetrics.hairline)
            }
        }
    }
}

// Detekt: the row forwards one callback per available action; bundling them
// into a holder would hide which actions the row offers.
@Suppress("LongParameterList")
@Composable
private fun SessionItem(
    session: SessionSummary,
    forkAvailable: Boolean,
    busy: Boolean,
    mutationsEnabled: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onFork: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val moreActionsDescription = stringResource(Res.string.ui_more_actions)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SessionRow(
            title = session.title,
            meta = session.directory ?: session.projectId ?: session.id,
            status = SessionRowStatus.Idle,
            onClick = onOpen,
            busy = busy,
            enabled = true, // reading is allowed offline; only the mutations are gated
            modifier = Modifier.weight(1f),
        )
        Box {
            TextButton(
                onClick = { menuOpen = true },
                enabled = !busy,
                modifier = Modifier.semantics {
                    contentDescription = moreActionsDescription
                },
            ) {
                Text("…")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.session_action_open), style = OpenCodeType.control) },
                    onClick = { menuOpen = false; onOpen() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.session_action_rename), style = OpenCodeType.control) },
                    enabled = mutationsEnabled,
                    onClick = { menuOpen = false; onRename() },
                )
                if (forkAvailable) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.session_action_fork), style = OpenCodeType.control) },
                        enabled = mutationsEnabled,
                        onClick = { menuOpen = false; onFork() },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.session_action_delete), style = OpenCodeType.control) },
                    enabled = mutationsEnabled,
                    onClick = { menuOpen = false; onDelete() },
                )
            }
        }
    }
}

@Composable
private fun RenameDialog(
    session: SessionSummary,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var title by remember(session.id) { mutableStateOf(session.title) }
    val normalized = SessionTitlePolicy.normalize(title)
    val valid = normalized != null && SessionTitlePolicy.isValid(normalized)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.session_rename_title), style = OpenCodeType.section) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(OpenCodeSpacing.x2)) {
                Text(session.id, style = OpenCodeType.meta, color = LocalOpenCodeColors.current.textMuted)
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                    isError = !valid,
                    label = { Text(stringResource(Res.string.session_title_label)) },
                )
                if (!valid) {
                    Text(
                        text = stringResource(Res.string.session_title_invalid, SessionTitlePolicy.MAX_LENGTH),
                        style = OpenCodeType.meta,
                        color = LocalOpenCodeColors.current.danger,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { normalized?.let(onConfirm) },
                enabled = valid,
            ) {
                Text(stringResource(Res.string.session_action_rename), style = OpenCodeType.control)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.session_cancel), style = OpenCodeType.control)
            }
        },
    )
}

@Composable
private fun DeleteDialog(
    session: SessionSummary,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.session_delete_title), style = OpenCodeType.section) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(OpenCodeSpacing.x2)) {
                Text(session.title, style = OpenCodeType.bodyStrong)
                Text(
                    stringResource(Res.string.session_delete_body),
                    style = OpenCodeType.body,
                    color = LocalOpenCodeColors.current.textBody,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = LocalOpenCodeColors.current.danger,
                ),
            ) {
                Text(stringResource(Res.string.session_action_delete), style = OpenCodeType.control)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.session_cancel), style = OpenCodeType.control)
            }
        },
    )
}

@Composable
private fun SessionListError.message(): String = when (this) {
    SessionListError.ServerUnavailable ->
        stringResource(Res.string.session_error_unreachable)
    SessionListError.Offline ->
        stringResource(Res.string.session_error_offline)
    is SessionListError.NotFound ->
        stringResource(Res.string.session_error_not_found, sessionId)
    is SessionListError.Rejected -> message
}
