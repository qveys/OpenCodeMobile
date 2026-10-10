package org.opencodemobile.ios

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ExperimentalComposeApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AccessibilitySyncOptions
import androidx.compose.ui.window.ComposeUIViewController
import org.opencodemobile.design.system.LocalOpenCodeColors
import org.opencodemobile.design.system.OpenCodeContext
import org.opencodemobile.design.system.OpenCodeTheme
import org.opencodemobile.design.system.SessionRow
import org.opencodemobile.design.system.SessionRowStatus
import org.opencodemobile.features.catalog.CatalogUiState
import org.opencodemobile.features.catalog.ServerCatalogScreen
import org.opencodemobile.features.connection.ConnectionSetupContent
import org.opencodemobile.features.connection.ConnectionSetupUiState
import org.opencodemobile.features.permissions.PermissionBannerModel
import org.opencodemobile.features.permissions.PermissionConfirmationScreen
import org.opencodemobile.features.permissions.PermissionDecisionUi
import org.opencodemobile.features.permissions.PermissionEmphasis
import org.opencodemobile.features.questions.PendingQuestionUi
import org.opencodemobile.features.questions.PendingQuestionsScreen
import org.opencodemobile.features.questions.QuestionItemUi
import org.opencodemobile.features.questions.QuestionOptionUi
import org.opencodemobile.features.questions.QuestionsUiState
import org.opencodemobile.features.settings.EraseEverythingActions
import org.opencodemobile.features.settings.EraseEverythingScreen
import org.opencodemobile.features.settings.EraseEverythingUiState
import org.opencodemobile.features.settings.LocalAccessSettingsActions
import org.opencodemobile.features.settings.LocalAccessSettingsScreen
import org.opencodemobile.features.settings.localAccessStrings
import org.opencodemobile.features.transcript.TranscriptScreen
import org.opencodemobile.shared.application.chat.TranscriptState
import org.opencodemobile.shared.domain.chat.TranscriptMessage
import org.opencodemobile.shared.domain.chat.TranscriptPart
import org.opencodemobile.shared.domain.chat.TranscriptRole
import org.opencodemobile.shared.domain.connection.DomainError
import org.opencodemobile.shared.domain.connection.ServerInputProblem
import org.opencodemobile.shared.domain.localaccess.LocalAccessSettings
import org.opencodemobile.shared.domain.permission.PermissionDecision
import platform.UIKit.UIViewController

/**
 * OPE-356: hosts one V1 screen with fixed fixtures, for `xcrun simctl launch`
 * screenshots without XCUITest. Mirrors the Paparazzi fixtures of
 * `androidApp`'s `V1ScreenshotTest` (kept in sync by name, not shared: that
 * source set is JVM test code).
 *
 * Reached only from the Swift shell's `#if DEBUG` branch on the
 * `-OPEScreen <name>` launch argument; a release build never passes a name.
 * Returns null for an unknown name so the caller falls back to the real app.
 */
@OptIn(ExperimentalComposeApi::class)
public fun debugScreenViewController(name: String, accessibilitySyncAlways: Boolean = false): UIViewController? {
    val screen = debugScreens[name] ?: return null
    return ComposeUIViewController(
        configure = {
            if (accessibilitySyncAlways) accessibilitySyncOptions = AccessibilitySyncOptions.Always(null)
        },
    ) {
        OpenCodeTheme(
            context = if (screen.session) OpenCodeContext.Session else {
                if (isSystemInDarkTheme()) OpenCodeContext.ChromeDark else OpenCodeContext.Chrome
            },
        ) {
            Surface(Modifier.fillMaxSize(), color = LocalOpenCodeColors.current.bg) {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) { screen.content() }
            }
        }
    }
}

private class DebugScreen(val session: Boolean = false, val content: @Composable () -> Unit)

private val noEraseActions = EraseEverythingActions({}, {}, {}, {})

private fun message(id: String, role: TranscriptRole, text: String) =
    TranscriptMessage(id, "s1", role, listOf(TranscriptPart("$id-p", "text", text)))

private val debugScreens: Map<String, DebugScreen> = mapOf(
    "manual-entry" to DebugScreen {
        ConnectionSetupContent(ConnectionSetupUiState(), {}, {}, {}, {}, {}, {}, {})
    },
    "invalid-address" to DebugScreen {
        val state = ConnectionSetupUiState(
            address = "http://",
            manualError = DomainError.InvalidServerAddress(ServerInputProblem.MISSING_HOST, "http://"),
        )
        ConnectionSetupContent(state, {}, {}, {}, {}, {}, {}, {})
    },
    "sessions" to DebugScreen {
        SessionRow("Refonte du module réseau", "il y a 2 min · mobile", SessionRowStatus.Running, {})
    },
    "transcript" to DebugScreen(session = true) {
        TranscriptScreen(
            TranscriptState(
                sessionId = "s1",
                messages = listOf(
                    message("m1", TranscriptRole.User, "Corrige le test qui échoue sur la synchronisation."),
                    message("m2", TranscriptRole.Assistant, "J'ai trouvé la cause : un délai de reconnexion trop court."),
                ),
            ),
        )
    },
    "permission" to DebugScreen(session = true) {
        PermissionConfirmationScreen(
            model = PermissionBannerModel(
                requestId = "p1",
                tool = "bash",
                targets = listOf("./gradlew test"),
                argumentsText = "{\"command\":\"./gradlew test\"}",
                decisions = listOf(
                    PermissionDecisionUi(PermissionDecision.Once, "Autoriser une fois", PermissionEmphasis.Primary, false),
                    PermissionDecisionUi(PermissionDecision.Deny, "Refuser", PermissionEmphasis.Danger, false),
                ),
                contentFingerprint = "a1b2c3",
            ),
            onDecision = {},
        )
    },
    "question" to DebugScreen(session = true) {
        PendingQuestionsScreen(
            state = QuestionsUiState(
                questions = listOf(
                    PendingQuestionUi(
                        "q1",
                        "s1",
                        listOf(
                            QuestionItemUi(
                                header = "Branche",
                                question = "Sur quelle branche appliquer le correctif ?",
                                options = listOf(QuestionOptionUi("main"), QuestionOptionUi("release", "Branche de publication")),
                            ),
                        ),
                    ),
                ),
            ),
            onReply = { _, _ -> },
            onReject = {},
        )
    },
    "local-access" to DebugScreen {
        LocalAccessSettingsScreen(
            state = LocalAccessSettings(),
            actions = LocalAccessSettingsActions({}, {}, {}),
            strings = localAccessStrings(),
            onBack = {},
        )
    },
    "erase-idle" to DebugScreen { EraseEverythingScreen(EraseEverythingUiState.Idle, noEraseActions) },
    "erase-confirming" to DebugScreen { EraseEverythingScreen(EraseEverythingUiState.Confirming, noEraseActions) },
    "catalog-loading" to DebugScreen { ServerCatalogScreen(CatalogUiState(loading = true), onRetry = {}) },
    "catalog-failed" to DebugScreen {
        ServerCatalogScreen(CatalogUiState(error = "Impossible de lire le catalogue du serveur."), onRetry = {})
    },
)
