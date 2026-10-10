package org.opencodemobile.design.system

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** The active context's semantic colours. */
public val LocalOpenCodeColors = staticCompositionLocalOf { OpenCodeColors.Chrome }

/** The active context. */
public val LocalOpenCodeContext = staticCompositionLocalOf { OpenCodeContext.Chrome }

/**
 * The V1 type scale (`docs/DESIGN-SYSTEM.md` §6). One family, `mono`.
 *
 * IBM Plex Mono is the face people actually see; until it is bundled the app
 * falls back through the platform monospace family, which keeps the layout and
 * the technical tone intact without shipping an unlicensed font.
 */
public object OpenCodeType {
    private val mono = FontFamily.Monospace

    public val title: TextStyle =
        TextStyle(fontFamily = mono, fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 32.sp)
    public val section: TextStyle =
        TextStyle(fontFamily = mono, fontSize = 17.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp)
    public val body: TextStyle =
        TextStyle(fontFamily = mono, fontSize = 16.sp, fontWeight = FontWeight.Normal, lineHeight = 24.sp)
    public val bodyStrong: TextStyle =
        TextStyle(fontFamily = mono, fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 24.sp)
    public val control: TextStyle =
        TextStyle(fontFamily = mono, fontSize = 15.sp, fontWeight = FontWeight.Medium, lineHeight = 22.sp)
    public val label: TextStyle =
        TextStyle(
            fontFamily = mono,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 18.sp,
            letterSpacing = 0.06.em,
        )
    public val tech: TextStyle =
        TextStyle(fontFamily = mono, fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 20.sp)
    public val meta: TextStyle =
        TextStyle(fontFamily = mono, fontSize = 12.sp, fontWeight = FontWeight.Normal, lineHeight = 18.sp)
}

/**
 * Applies the V1 chrome/session tokens to the Compose tree.
 *
 * Material 3 is used only as the rendering substrate: the scheme is built from
 * the semantic tokens, so a Material3 control picks up the right colours, and
 * [OpenCodeType]/[LocalOpenCodeColors] carry the exact token values a screen
 * needs. No component hard-codes a hex colour.
 */
@Composable
public fun OpenCodeTheme(
    context: OpenCodeContext = OpenCodeContext.Chrome,
    content: @Composable () -> Unit,
) {
    val colors = when (context) {
        OpenCodeContext.Chrome -> OpenCodeColors.Chrome
        OpenCodeContext.ChromeDark -> OpenCodeColors.ChromeDark
        OpenCodeContext.Session -> OpenCodeColors.Session
    }

    val scheme = when (context) {
        OpenCodeContext.Chrome -> lightColorScheme(
            background = colors.bg,
            onBackground = colors.text,
            surface = colors.bg,
            onSurface = colors.text,
            surfaceVariant = colors.bgPanel,
            onSurfaceVariant = colors.textBody,
            primary = colors.primary,
            onPrimary = colors.onPrimary,
            error = colors.danger,
            onError = colors.onDanger,
            outline = colors.line,
            outlineVariant = colors.lineStrong,
            surfaceContainerHighest = colors.bgRaised, // neutral Switch track, not the Material lavender
        )

        OpenCodeContext.ChromeDark, OpenCodeContext.Session -> darkColorScheme(
            background = colors.bg,
            onBackground = colors.text,
            surface = colors.bg,
            onSurface = colors.text,
            surfaceVariant = colors.bgPanel,
            onSurfaceVariant = colors.textBody,
            primary = colors.primary,
            onPrimary = colors.onPrimary,
            error = colors.danger,
            onError = colors.onDanger,
            outline = colors.line,
            outlineVariant = colors.lineStrong,
            surfaceContainerHighest = colors.bgRaised, // neutral Switch track, not the Material lavender
        )
    }

    CompositionLocalProvider(
        LocalOpenCodeColors provides colors,
        LocalOpenCodeContext provides context,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            content = content,
        )
    }
}
