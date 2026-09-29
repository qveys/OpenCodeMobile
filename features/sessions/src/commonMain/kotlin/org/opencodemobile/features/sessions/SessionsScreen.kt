package org.opencodemobile.features.sessions

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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.opencodemobile.design.system.LocalOpenCodeColors
import org.opencodemobile.design.system.OpenCodeContext
import org.opencodemobile.design.system.OpenCodeMetrics
import org.opencodemobile.design.system.OpenCodeSpacing
import org.opencodemobile.design.system.OpenCodeTheme
import org.opencodemobile.design.system.OpenCodeType
import org.opencodemobile.design.system.SessionRow
import org.opencodemobile.design.system.SessionRowStatus
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
) {
    OpenCodeTheme(context = OpenCodeContext.Chrome) {
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
private fun SessionsTopBar(offline: Boolean, onNewSession: () -> Unit) {
    val colors = LocalOpenCodeColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = OpenCodeSpacing.x4, vertical = OpenCodeSpacing.x3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Sessions",
            style = OpenCodeType.title,
            modifier = Modifier.weight(1f),
        )
        // D8: the mutation affordance is disabled offline, not merely refused.
        Button(
            onClick = onNewSession,
            enabled = !offline,
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.primary,
                contentColor = colors.onPrimary,
            ),
        ) {
            Text("New session", style = OpenCodeType.control)
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
        state.offline -> "Offline — showing cached sessions. Mutations are disabled."
        state.error != null -> state.error!!.message()
        else -> state.notice
    }
    if (message == null) {
        if (!state.loading && state.sessions.isNotEmpty()) {
            Text(
                text = "${state.sessions.size} session(s) · live",
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
                Text("Retry", style = OpenCodeType.control)
            }
        }
    }
}

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
            Text("Loading sessions…", style = OpenCodeType.tech, color = colors.textMuted)
        }

        state.sessions.isEmpty() -> Column(
            modifier = Modifier.fillMaxSize().padding(OpenCodeSpacing.x4),
            verticalArrangement = Arrangement.spacedBy(OpenCodeSpacing.x3),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = if (state.error != null) {
                    "Sessions could not be listed."
                } else {
                    "No sessions yet."
                },
                style = OpenCodeType.section,
            )
            Text(
                text = if (state.offline) {
                    "There is nothing cached for this project yet."
                } else {
                    "Create a session to start an agent turn."
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
                    Text("New session", style = OpenCodeType.control)
                }
            }
        }

        else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(state.sessions, key = { it.id }) { session ->
                SessionItem(
                    session = session,
                    forkAvailable = state.forkAvailable,
                    busy = state.pendingSessionId == session.id,
                    enabled = !state.offline,
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

@Composable
private fun SessionItem(
    session: SessionSummary,
    forkAvailable: Boolean,
    busy: Boolean,
    enabled: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onFork: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
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
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        Box {
            TextButton(onClick = { menuOpen = true }, enabled = enabled && !busy) {
                Text("…")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Open", style = OpenCodeType.control) },
                    onClick = { menuOpen = false; onOpen() },
                )
                DropdownMenuItem(
                    text = { Text("Rename", style = OpenCodeType.control) },
                    onClick = { menuOpen = false; onRename() },
                )
                if (forkAvailable) {
                    DropdownMenuItem(
                        text = { Text("Fork", style = OpenCodeType.control) },
                        onClick = { menuOpen = false; onFork() },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Delete", style = OpenCodeType.control) },
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
        title = { Text("Rename session", style = OpenCodeType.section) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(OpenCodeSpacing.x2)) {
                Text(session.id, style = OpenCodeType.meta, color = LocalOpenCodeColors.current.textMuted)
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                    isError = !valid,
                    label = { Text("Title") },
                )
                if (!valid) {
                    Text(
                        text = "A title must be 1..${SessionTitlePolicy.MAX_LENGTH} characters.",
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
                Text("Rename", style = OpenCodeType.control)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", style = OpenCodeType.control)
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
        title = { Text("Delete session", style = OpenCodeType.section) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(OpenCodeSpacing.x2)) {
                Text(session.title, style = OpenCodeType.bodyStrong)
                Text(
                    "This deletes the session and its transcript on the server. It cannot be undone.",
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
                Text("Delete", style = OpenCodeType.control)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", style = OpenCodeType.control)
            }
        },
    )
}

private fun SessionListError.message(): String = when (this) {
    SessionListError.ServerUnavailable ->
        "Cannot reach the OpenCode server. Check the connection, then retry."
    SessionListError.Offline ->
        "Offline — cached sessions are read-only."
    is SessionListError.NotFound ->
        "Session $sessionId no longer exists on the server."
    is SessionListError.Rejected -> message
}
