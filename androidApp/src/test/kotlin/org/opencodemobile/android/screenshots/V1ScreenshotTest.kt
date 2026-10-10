package org.opencodemobile.android.screenshots

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
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
import org.opencodemobile.features.connection.ServerImportReviewScreen
import org.opencodemobile.features.sessions.SessionsPresenter
import org.opencodemobile.features.sessions.SessionsScreen
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
import org.opencodemobile.shared.application.connection.ServerSetupPlan
import org.opencodemobile.shared.application.connection.ServerSetupSource
import org.opencodemobile.shared.application.session.SessionListController
import org.opencodemobile.shared.application.session.SessionsScope
import org.opencodemobile.shared.domain.cache.MutationGate
import org.opencodemobile.shared.domain.cache.SessionCache
import org.opencodemobile.shared.domain.chat.ChatPrompt
import org.opencodemobile.shared.domain.chat.OpenCodeChatGateway
import org.opencodemobile.shared.domain.chat.TranscriptMessage
import org.opencodemobile.shared.domain.chat.TranscriptPart
import org.opencodemobile.shared.domain.chat.TranscriptRole
import org.opencodemobile.shared.domain.connection.DomainError
import org.opencodemobile.shared.domain.connection.ServerFingerprint
import org.opencodemobile.shared.domain.connection.ServerInputProblem
import org.opencodemobile.shared.domain.connection.ServerProfile
import org.opencodemobile.shared.domain.dictation.DictationAvailability
import org.opencodemobile.shared.domain.dictation.DictationEvent
import org.opencodemobile.shared.domain.dictation.DictationProvider
import org.opencodemobile.shared.domain.dictation.DictationUnavailableReason
import org.opencodemobile.shared.domain.localaccess.LocalAccessSettings
import org.opencodemobile.shared.domain.session.SessionCapabilities
import org.opencodemobile.shared.domain.session.SessionGateway
import org.opencodemobile.shared.domain.session.SessionSummary

/**
 * OPE-352: one PNG per V1 screen state, rendered on the JVM (no emulator).
 *
 * Record: `./gradlew :androidApp:recordPaparazziDebug`; verify: `verifyPaparazziDebug`.
 * Each screen is rendered for fr/en x light/dark; fixture copy goes through [tr] so the
 * images are deterministic and the language matches the locale.
 */
@RunWith(Parameterized::class)
class V1ScreenshotTest(private val locale: String, private val dark: Boolean) {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            locale = locale,
            nightMode = if (dark) NightMode.NIGHT else NightMode.NOTNIGHT,
        ),
    )

    private fun tr(fr: String, en: String) = if (locale == "fr") fr else en

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}-{1}")
        fun params() = listOf("fr", "en").flatMap { l -> listOf(false, true).map { arrayOf<Any>(l, it) } }
    }

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
            // Session is already the dark context; only Chrome has a dark twin.
            val themed = if (dark && context == OpenCodeContext.Chrome) OpenCodeContext.ChromeDark else context
            OpenCodeTheme(context = themed) {
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
            agents = listOf(AgentUi("build", tr("Agent de développement par défaut", "Default development agent"), "primary")),
        )
        ServerCatalogScreen(state, onRetry = {})
    }

    @Test
    fun connection_review() = shot {
        val plan = ServerSetupPlan(
            profile = ServerProfile("srv", "192.168.1.20", 4096, "Mac mini"),
            source = ServerSetupSource.QrCode,
            fingerprint = ServerFingerprint.of(ByteArray(32) { it.toByte() }),
        )
        ServerImportReviewScreen(ConnectionSetupUiState(review = plan), {}, {}, {})
    }

    // 2. Sessions

    // Home after connecting: the real SessionsScreen over a fake gateway.
    @Test
    fun home_sessions() = shot {
        val sessions = listOf(
            SessionSummary("s1", tr("Refonte du module réseau", "Networking module rewrite"), updatedAt = 2_000),
            SessionSummary("s2", tr("Correctif de synchronisation", "Sync fix"), updatedAt = 1_000),
        )
        val gateway = object : SessionGateway {
            override suspend fun listSessions(directory: String?) = sessions

            override suspend fun getSession(sessionId: String) = sessions.first()

            override suspend fun createSession(directory: String?, title: String?) = sessions.first()

            override suspend fun renameSession(sessionId: String, title: String) = sessions.first()

            override suspend fun deleteSession(sessionId: String) = Unit

            override suspend fun forkSession(sessionId: String) = sessions.first()

            override suspend fun sessionCapabilities(directory: String?) = SessionCapabilities(forkAvailable = true)
        }
        val cache = object : SessionCache { // unused: the gate is open and the gateway never fails
            override suspend fun serverConfig(serverId: String) = null

            override suspend fun projects(serverId: String) = emptyList<org.opencodemobile.shared.domain.cache.CachedProject>()

            override suspend fun sessions(serverId: String, projectId: String) = emptyList<org.opencodemobile.shared.domain.cache.CachedSession>()

            override suspend fun session(serverId: String, projectId: String, sessionId: String) = null

            override suspend fun recentTranscript(serverId: String, projectId: String, sessionId: String, limit: Int) =
                emptyList<org.opencodemobile.shared.domain.cache.CachedTranscriptMessage>()

            override suspend fun draft(serverId: String, projectId: String, sessionId: String) = null

            override suspend fun preference(serverId: String, key: String) = null

            override suspend fun syncMetadata(serverId: String, projectId: String, sessionId: String) = null
        }
        val controller = SessionListController(gateway, cache, object : MutationGate { override fun mutationsAllowed() = true }, { SessionsScope("srv", "p1") })
        SessionsScreen(SessionsPresenter(controller, CoroutineScope(Dispatchers.Unconfined)), onOpenSession = {})
    }

    @Test
    fun sessions_list() = shot {
        SessionRow(tr("Refonte du module réseau", "Networking module rewrite"), tr("il y a 2 min · mobile", "2 min ago · mobile"), SessionRowStatus.Running, {})
    }

    // 3. Chat

    @Test
    fun chat_transcript() = shot(OpenCodeContext.Session) {
        TranscriptScreen(
            TranscriptState(
                sessionId = "s1",
                messages = listOf(
                    message("m1", TranscriptRole.User, tr("Corrige le test qui échoue sur la synchronisation.", "Fix the test that fails on sync.")),
                    message("m2", TranscriptRole.Assistant, tr("J'ai trouvé la cause : un délai de reconnexion trop court.", "Found the cause: the reconnect delay is too short.")),
                ),
            ),
        )
    }

    @Test
    fun chat_error() = shot(OpenCodeContext.Session) {
        TranscriptScreen(TranscriptState(sessionId = "s1", error = tr("Connexion perdue", "Connection lost")))
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
                    PermissionDecisionUi(org.opencodemobile.shared.domain.permission.PermissionDecision.Once, tr("Autoriser une fois", "Allow once"), PermissionEmphasis.Primary, false),
                    PermissionDecisionUi(org.opencodemobile.shared.domain.permission.PermissionDecision.Deny, tr("Refuser", "Deny"), PermissionEmphasis.Danger, false),
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
                                header = tr("Branche", "Branch"),
                                question = tr("Sur quelle branche appliquer le correctif ?", "Which branch should the fix go on?"),
                                options = listOf(QuestionOptionUi("main"), QuestionOptionUi("release", tr("Branche de publication", "Release branch"))),
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
        ComposerScreen(composer(DictationAvailability.Available(tag), draft = tr("Ajoute un test de reconnexion", "Add a reconnect test")), tag)
    }

    @Test
    fun dictation_unavailable() = shot(OpenCodeContext.Session) {
        ComposerScreen(composer(DictationAvailability.Unavailable(DictationUnavailableReason.OnDeviceModelMissing)), tag)
    }

    @Test
    fun dictation_listening() = shot(OpenCodeContext.Session) {
        val presenter = composer(DictationAvailability.Available(tag), partial = tr("ajoute un test", "add a test"))
        ComposerScreen(presenter, tag)
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
        ServerCatalogScreen(CatalogUiState(error = tr("Impossible de lire le catalogue du serveur.", "Could not read the server catalog.")), onRetry = {})
    }

    @Test
    fun error_catalog_loading() = shot {
        ServerCatalogScreen(CatalogUiState(loading = true), onRetry = {})
    }

    @Test
    fun error_erase_reauth_failed() = shot {
        EraseEverythingScreen(EraseEverythingUiState.ReauthenticationFailed, noEraseActions)
    }

    private val tag get() = if (locale == "fr") "fr-FR" else "en-US"

    private val noEraseActions = EraseEverythingActions({}, {}, {}, {})

    private fun message(id: String, role: TranscriptRole, text: String) =
        TranscriptMessage(id, "s1", role, listOf(TranscriptPart("$id-p", "text", text)))

    /** Eager scope: every controller coroutine settles during composition. */
    @Suppress("InjectDispatcher") // test-only controller scope, no DI
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
        val controller = ComposerController(chat, provider, null, "s1", CoroutineScope(Dispatchers.Unconfined), tag)
        controller.start(tag)
        controller.onDraftChange(draft)
        return DictationComposerPresenter(controller)
    }
}
