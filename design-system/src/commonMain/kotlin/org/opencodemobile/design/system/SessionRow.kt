// Detekt: this file owns the session row and its status enum. The enum cannot
// match the file name, and the row takes the design-system parameter surface.
@file:Suppress("MatchingDeclarationName", "LongParameterList")
package org.opencodemobile.design.system

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.opencodemobile.design.system.resources.Res
import org.opencodemobile.design.system.resources.ui_session_accessibility
import org.opencodemobile.design.system.resources.ui_session_status_attention
import org.opencodemobile.design.system.resources.ui_session_status_completed
import org.opencodemobile.design.system.resources.ui_session_status_failed
import org.opencodemobile.design.system.resources.ui_session_status_idle
import org.opencodemobile.design.system.resources.ui_session_status_running

/**
 * The session status shown by a [SessionRow] glyph
 * (`docs/DESIGN-SYSTEM.md` §10 "Status").
 */
public enum class SessionRowStatus {
    /** `○` — no active turn. */
    Idle,

    /** `●` — an agent turn is running. */
    Running,

    /** `[!]` — attention needed (permission, error). */
    Attention,

    /** `[+]` — the last turn completed. */
    Completed,

    /** `[x]` — the last turn failed. */
    Failed,
}

/** The ASCII glyph a status renders, per the design system. */
public fun SessionRowStatus.glyph(): String = when (this) {
    SessionRowStatus.Idle -> "○"
    SessionRowStatus.Running -> "●"
    SessionRowStatus.Attention -> "[!]"
    SessionRowStatus.Completed -> "[+]"
    SessionRowStatus.Failed -> "[x]"
}

/**
 * One session row (`docs/DESIGN-SYSTEM.md` §10 "SessionRow").
 *
 * Full-width, no card wrapper: status glyph in a fixed column, title, muted
 * technical meta line, and an optional trailing value. Geometry is
 * `row-min..row-max`; the caller provides the tap action and never a server
 * capability — a disabled fork is expressed by not rendering its action.
 */
@Composable
public fun SessionRow(
    title: String,
    meta: String,
    status: SessionRowStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    busy: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = LocalOpenCodeColors.current
    val glyphColor: Color = when (status) {
        SessionRowStatus.Idle -> colors.textMuted
        SessionRowStatus.Running -> colors.agent
        SessionRowStatus.Attention -> colors.warning
        SessionRowStatus.Completed -> colors.success
        SessionRowStatus.Failed -> colors.danger
    }
    val localizedStatus = stringResource(
        when (status) {
            SessionRowStatus.Idle -> Res.string.ui_session_status_idle
            SessionRowStatus.Running -> Res.string.ui_session_status_running
            SessionRowStatus.Attention -> Res.string.ui_session_status_attention
            SessionRowStatus.Completed -> Res.string.ui_session_status_completed
            SessionRowStatus.Failed -> Res.string.ui_session_status_failed
        },
    )
    val accessibilityDescription = stringResource(
        Res.string.ui_session_accessibility,
        title,
        localizedStatus,
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = OpenCodeMetrics.rowMin, max = OpenCodeMetrics.rowMax)
            .clickable(enabled = enabled && !busy, onClick = onClick)
            .background(if (busy) colors.bgRaised else colors.bg)
            .padding(horizontal = OpenCodeSpacing.x4, vertical = OpenCodeSpacing.x3)
            .semantics { contentDescription = accessibilityDescription },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.widthIn(min = GLYPH_COLUMN), contentAlignment = Alignment.Center) {
            Text(
                text = status.glyph(),
                style = OpenCodeType.tech,
                color = glyphColor,
            )
        }
        Column(
            modifier = Modifier.padding(start = OpenCodeSpacing.x3).weight(1f),
            verticalArrangement = Arrangement.spacedBy(OpenCodeSpacing.x1),
        ) {
            Text(
                text = title,
                style = OpenCodeType.bodyStrong,
                color = if (enabled) colors.text else colors.textWeak,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = meta,
                style = OpenCodeType.meta,
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (trailing != null) {
            Text(
                text = trailing,
                style = OpenCodeType.meta,
                color = colors.textMuted,
                modifier = Modifier.padding(start = OpenCodeSpacing.x2),
            )
        }
    }
}

private val GLYPH_COLUMN: Dp = 28.dp
