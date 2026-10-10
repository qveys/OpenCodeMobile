package org.opencodemobile.android.screenshots

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.opencodemobile.design.system.LocalOpenCodeColors
import org.opencodemobile.design.system.OpenCodeContext
import org.opencodemobile.design.system.OpenCodeTheme
import org.opencodemobile.design.system.SessionRow
import org.opencodemobile.design.system.SessionRowStatus
import org.opencodemobile.features.catalog.AgentUi
import org.opencodemobile.features.catalog.CatalogUiState
import org.opencodemobile.features.catalog.ModelUi
import org.opencodemobile.features.catalog.ProviderUi
import org.opencodemobile.features.catalog.ServerCatalogScreen
import org.opencodemobile.features.composer.DictationComposerPresenter
import org.opencodemobile.features.composer.ComposerScreen
import org.opencodemobile.features.connection.ConnectionFailure
import org.opencodemobile.features.connection.ConnectionSetupContent
import org.opencodemobile.features.connection.ConnectionSetupUiState
import org.opencodemobile.features.connection.FailureKind
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
import org.opencodemobile.shared.application.composer.ComposerController
import org.opencodemobile.shared.domain.chat.ChatPrompt
import org.opencodemobile.shared.domain.chat.OpenCodeChatGateway
import org.opencodemobile.shared.domain.chat.TranscriptMessage
import org.opencodemobile.shared.domain.chat.TranscriptPart
import org.opencodemobile.shared.domain.chat.TranscriptRole
import org.opencodemobile.shared.domain.connection.DomainError
import org.opencodemobile.shared.domain.connection.ServerInputProblem
import org.opencodemobile.shared.domain.dictation.DictationAvailability
import org.opencodemobile.shared.domain.dictation.DictationEvent
import org.opencodemobile.shared.domain.dictation.DictationProvider
import org.opencodemobile.shared.domain.dictation.DictationUnavailableReason
import org.opencodemobile.shared.domain.localaccess.LocalAccessSettings

/**
 * OPE-352: one PNG per V1 screen state, rendered on the JVM (no emulator).
 *
 * Record: `./gradlew :androidApp:recordPaparazziDebug`; verify: `verifyPaparazziDebug`.
 * Fixtures are fixed French copy so the images are deterministic.
 */
class V1ScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(locale = "fr"))

    // CMP resources read assets through a context that its ContentProvider sets
    // at app start; Paparazzi never starts providers, so set the static by hand.
    @Before
    fun initComposeResources() {
        Class.forName("org.jetbrains.compose.resources.AndroidContextProvider")
            .getDeclaredField("ANDROID_CONTEXT")
            .apply { isAccessible = true }
            .set(null, paparazzi.context)
    }

    private fun shot(context: OpenCodeContext = OpenCodeContext.Chrome, content: @Composable () -> Unit) =
        paparazzi.snapshot {
            OpenCodeTheme(context = context) {
                // Some screens draw no background of their own (the app shell does).
                Surface(Modifier.fillMaxSize(), color = LocalOpenCodeColors.current.bg) { content() }
            }
        }

    // 1. Connexion / serveur

    @Test
    fun connection_manual_entry() = shot {
        ConnectionSetupContent(ConnectionSetupUiState(), {}, {}, {}, {}, {}, {}, {})
    }

    @Test
    fun connection_invalid_address() = shot {
        val state = ConnectionSetupUiState(
            address = "http://",
            manualError = DomainError.InvalidServerAddress(ServerInputProblem.MISSING_HOST, "http://"),
        )
        ConnectionSetupContent(state, {}, {}, {}, {}, {}, {}, {})
    }

    @Test
    fun connection_unreachable() = shot {
        val state = ConnectionSetupUiState(
            address = "192.168.1.20:4096",
            failure = ConnectionFailure(FailureKind.UNREACHABLE),
        )
        ConnectionSetupContent(state, {}, {}, {}, {}, {}, {}, {})
    }

    @Test
    fun connection_catalog() = shot {
        val state = CatalogUiState(
            providers = listOf(
                ProviderUi("anthropic", "Anthropic", listOf(ModelUi("sonnet", "Claude Sonnet", "anthropic"))),
                ProviderUi("openai", "OpenAI", listOf(ModelUi("gpt", "GPT", "openai"))),
            ),
            agents = listOf(AgentUi("build", "Agent de développement par défaut", "primary")),
        )
        ServerCatalogScreen(state, onRetry = {})
    }

    // 2. Sessions

    @Test
    fun sessions_list() = shot {
        SessionRow("Refonte du module réseau", "il y a 2 min · mobile", SessionRowStatus.Running, {})
    }

    // 3. Chat

    // The assistant body is Markdown parsed off-thread: one frame shows the role label only.
    @Test
    fun chat_transcript() = shot(OpenCodeContext.Session) {
        TranscriptScreen(
            TranscriptState(
                sessionId = "s1",
                messages = listOf(
                    message("m1", TranscriptRole.User, "Corrige le test qui échoue sur la synchronisation."),
                    message("m2", TranscriptRole.Assistant, "J'ai trouvé la cause : un délai de reconnexion trop court."),
                ),
            ),
        )
    }

    @Test
    fun chat_error() = shot(OpenCodeContext.Session) {
        TranscriptScreen(TranscriptState(sessionId = "s1", error = "Connexion perdue"))
    }

    @Test
    fun chat_permission() = shot {
        PermissionConfirmationScreen(
            model = PermissionBannerModel(
                requestId = "p1",
                tool = "bash",
                targets = listOf("./gradlew test"),
                argumentsText = "{\"command\":\"./gradlew test\"}",
                decisions = listOf(
                    PermissionDecisionUi(org.opencodemobile.shared.domain.permission.PermissionDecision.Once, "Autoriser une fois", PermissionEmphasis.Primary, false),
                    PermissionDecisionUi(org.opencodemobile.shared.domain.permission.PermissionDecision.Deny, "Refuser", PermissionEmphasis.Danger, false),
                ),
                contentFingerprint = "a1b2c3",
            ),
            onDecision = {},
        )
    }

    @Test
    fun chat_question() = shot(OpenCodeContext.Session) {
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
    }

    // 4. Dictée

    @Test
    fun dictation_available() = shot(OpenCodeContext.Session) {
        ComposerScreen(composer(DictationAvailability.Available("fr-FR"), draft = "Ajoute un test de reconnexion"), "fr-FR")
    }

    @Test
    fun dictation_unavailable() = shot(OpenCodeContext.Session) {
        ComposerScreen(composer(DictationAvailability.Unavailable(DictationUnavailableReason.OnDeviceModelMissing)), "fr-FR")
    }

    @Test
    fun dictation_listening() = shot(OpenCodeContext.Session) {
        val presenter = composer(DictationAvailability.Available("fr-FR"), partial = "ajoute un test")
        ComposerScreen(presenter, "fr-FR")
        // After the screen's own locale check, which stops any session.
        LaunchedEffect(Unit) { presenter.toggleDictation() }
    }

    // 5. Réglages

    @Test
    fun settings_local_access() = shot {
        LocalAccessSettingsScreen(
            state = LocalAccessSettings(),
            actions = LocalAccessSettingsActions({}, {}, {}),
            strings = localAccessStrings(),
            onBack = {},
        )
    }

    @Test
    fun settings_erase_idle() = shot {
        EraseEverythingScreen(EraseEverythingUiState.Idle, noEraseActions)
    }

    @Test
    fun settings_erase_confirming() = shot {
        EraseEverythingScreen(EraseEverythingUiState.Confirming, noEraseActions)
    }

    // 6. États d'erreur

    @Test
    fun error_catalog_failed() = shot {
        ServerCatalogScreen(CatalogUiState(error = "Impossible de lire le catalogue du serveur."), onRetry = {})
    }

    @Test
    fun error_catalog_loading() = shot {
        ServerCatalogScreen(CatalogUiState(loading = true), onRetry = {})
    }

    @Test
    fun error_erase_reauth_failed() = shot {
        EraseEverythingScreen(EraseEverythingUiState.ReauthenticationFailed, noEraseActions)
    }

    private val noEraseActions = EraseEverythingActions({}, {}, {}, {})

    private fun message(id: String, role: TranscriptRole, text: String) =
        TranscriptMessage(id, "s1", role, listOf(TranscriptPart("$id-p", "text", text)))

    /** Eager scope: every controller coroutine settles during composition. */
    private fun composer(
        availability: DictationAvailability,
        draft: String = "",
        partial: String? = null,
    ): DictationComposerPresenter {
        val provider = object : DictationProvider {
            override suspend fun availability(locale: String) = availability

            override fun transcribe(locale: String): Flow<DictationEvent> = flow {
                partial?.let { emit(DictationEvent.Partial(it)) }
                awaitCancellation()
            }

            override suspend fun cancel() = Unit
        }
        val chat = object : OpenCodeChatGateway {
            override suspend fun transcript(sessionId: String) = emptyList<TranscriptMessage>()

            override suspend fun sendPrompt(sessionId: String, prompt: ChatPrompt) = Unit
        }
        val controller = ComposerController(chat, provider, null, "s1", CoroutineScope(Dispatchers.Unconfined), "fr-FR")
        controller.start("fr-FR")
        controller.onDraftChange(draft)
        return DictationComposerPresenter(controller)
    }
}
