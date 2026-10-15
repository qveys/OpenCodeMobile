package org.opencodemobile.features.transcript

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.highlightedCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.model.rememberMarkdownState

/**
 * Renders one assistant message as Markdown (V1-05).
 *
 * Per ADR 0006 the renderer is `com.mikepenz:multiplatform-markdown-renderer`
 * (Material 3 module) and syntax highlighting is the `-code` module backed by
 * Highlights. Fenced code blocks are syntax-coloured, and the whole message is
 * wrapped in a [SelectionContainer] so code (and prose) is selectable and
 * copyable with the platform selection menu.
 *
 * **Version note.** ADR 0006 pinned `0.45.0`, which cannot be built here (Kotlin
 * 2.4 metadata; and `0.35.0` links Compose `ui-backhandler`, CMP 1.8+). The
 * deviation is recorded in **ADR 0007**: `0.33.0` is the newest release
 * compatible with this repo's Kotlin 2.1.0 / CMP 1.7.1 and still ships the
 * Highlights `-code` module. Its code fence has no built-in language header or
 * copy button, so selection is provided here via [SelectionContainer]; the
 * explicit copy affordance is a follow-up gated on the Kotlin/Compose upgrade.
 *
 * This is the single wrapper the ADR asks for: the library stays swappable
 * behind one composable. No image transformer is wired, so a remote image in
 * server Markdown can never make the device contact a third-party host.
 */
@Composable
public fun AssistantMarkdown(
    content: String,
    modifier: Modifier = Modifier,
) {
    SelectionContainer(modifier = modifier) {
        Markdown(
            markdownState = rememberMarkdownState(content, immediate = LocalMarkdownImmediate.current),
            components = transcriptMarkdownComponents(),
        )
    }
}

/** The design-system-aligned Markdown components, with highlighted code fences. */
@Composable
public fun transcriptMarkdownComponents(): MarkdownComponents =
    markdownComponents(
        codeFence = highlightedCodeFence,
    )

/** Debug fixtures (iOS screenshots) set this so the first frame already holds the parsed Markdown. */
public val LocalMarkdownImmediate = staticCompositionLocalOf { false }
