package org.opencodemobile.design.system

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The V1 design-system tokens (`docs/DESIGN-SYSTEM.md` §3–§9).
 *
 * These are the semantic token values that screens may consume. A screen never
 * hard-codes a hex colour or a raw spacing value in this app: it reads a token
 * from the context its [OpenCodeTheme] provides.
 */
public enum class OpenCodeContext {
    /** Onboarding, connect, projects, sessions list, settings — system light mode. */
    Chrome,

    /** The same chrome screens in system dark mode. */
    ChromeDark,

    /** An open session: transcript, tools, diffs, composer — always dark. */
    Session,
}

/** The semantic colour set of one [OpenCodeContext]. */
public data class OpenCodeColors(
    public val bg: Color,
    public val bgPanel: Color,
    public val bgRaised: Color,
    public val line: Color,
    public val lineStrong: Color,
    public val text: Color,
    public val textBody: Color,
    public val textMuted: Color,
    public val textWeak: Color,
    public val primary: Color,
    public val primaryPressed: Color,
    public val onPrimary: Color,
    public val agent: Color,
    public val interactive: Color,
    public val focus: Color,
    public val success: Color,
    public val warning: Color,
    public val danger: Color,
    public val onDanger: Color,
) {
    public companion object {
        /** `chrome` — the sessions list in system light mode. */
        public val Chrome: OpenCodeColors = OpenCodeColors(
            bg = Color(0xFFFDFCFC),
            bgPanel = Color(0xFFF8F7F7),
            bgRaised = Color(0xFFF1EEEE),
            line = Color(0x1F0F0000),
            lineStrong = Color(0xFF646262),
            text = Color(0xFF201D1D),
            textBody = Color(0xFF646262),
            textMuted = Color(0xFF646262),
            textWeak = Color(0xFF9A9898),
            primary = Color(0xFF201D1D),
            primaryPressed = Color(0xFF302C2C),
            onPrimary = Color(0xFFFDFCFC),
            agent = Color(0xFF954C27),
            interactive = Color(0xFF3B5CF6),
            focus = Color(0xFF3B5CF6),
            success = Color(0xFF1D783C),
            warning = Color(0xFF68552B),
            danger = Color(0xFFB82D35),
            onDanger = Color(0xFFFDFCFC),
        )

        /** `chrome-dark` — the same screens in system dark mode. */
        public val ChromeDark: OpenCodeColors = OpenCodeColors(
            bg = Color(0xFF131010),
            bgPanel = Color(0xFF1B1818),
            bgRaised = Color(0xFF292424),
            line = Color(0xFF3D3838),
            lineStrong = Color(0xFF7F7A7A),
            text = Color(0xFFF2EDED),
            textBody = Color(0xFFB8B2B2),
            textMuted = Color(0xFF9A9898),
            textWeak = Color(0xFF7F7A7A),
            primary = Color(0xFFF2EDED),
            primaryPressed = Color(0xFFF8F6F6),
            onPrimary = Color(0xFF131010),
            agent = Color(0xFFFAB283),
            interactive = Color(0xFFA2BCFF),
            focus = Color(0xFFA2BCFF),
            success = Color(0xFF12C905),
            warning = Color(0xFFFCD53A),
            danger = Color(0xFFFC533A),
            onDanger = Color(0xFF0A0A0A),
        )

        /** `session` — the open-session context, always dark. */
        public val Session: OpenCodeColors = OpenCodeColors(
            bg = Color(0xFF0A0A0A),
            bgPanel = Color(0xFF141414),
            bgRaised = Color(0xFF1C1C1C),
            line = Color(0xFF282828),
            lineStrong = Color(0xFF707070),
            text = Color(0xFFEEEEEE),
            textBody = Color(0xFFA0A0A0),
            textMuted = Color(0xFF808080),
            textWeak = Color(0xFF707070),
            primary = Color(0xFFFAB283),
            primaryPressed = Color(0xFFFFA478),
            onPrimary = Color(0xFF0A0A0A),
            agent = Color(0xFFFAB283),
            interactive = Color(0xFFA2BCFF),
            focus = Color(0xFFFAB283),
            success = Color(0xFF12C905),
            warning = Color(0xFFFCD53A),
            danger = Color(0xFFFC533A),
            onDanger = Color(0xFF0A0A0A),
        )
    }
}

/** 4 pt spacing grid (`docs/DESIGN-SYSTEM.md` §7). */
public object OpenCodeSpacing {
    public val x1: Dp = 4.dp
    public val x2: Dp = 8.dp
    public val x3: Dp = 12.dp
    public val x4: Dp = 16.dp
    public val x5: Dp = 20.dp
    public val x6: Dp = 24.dp
    public val x8: Dp = 32.dp
}

/** Geometry tokens (`docs/DESIGN-SYSTEM.md` §8–§9). */
public object OpenCodeMetrics {
    public val radiusSm: Dp = 4.dp
    public val radiusMd: Dp = 6.dp
    public val hitIos: Dp = 44.dp
    public val hitAndroid: Dp = 48.dp
    public val rowMin: Dp = 52.dp
    public val rowMax: Dp = 64.dp
    public val hairline: Dp = 1.dp
}

/** Container padding for a chrome screen: `space-4`, or `space-5` on larger widths. */
public object OpenCodeInsets {
    public val screen: Dp = OpenCodeSpacing.x4
}
