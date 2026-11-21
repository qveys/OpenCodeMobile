package org.opencodemobile.ios

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.opencodemobile.features.composer.ComposerPresenter
import org.opencodemobile.features.permissions.PermissionsPresenter
import org.opencodemobile.features.questions.QuestionsPresenter
import org.opencodemobile.features.sessions.SessionsPresenter
import org.opencodemobile.features.transcript.TranscriptPresenter
import org.opencodemobile.shared.application.cache.CacheScope
import org.opencodemobile.shared.application.cache.CacheWritePipeline
import org.opencodemobile.shared.application.cache.toConnectionState
import org.opencodemobile.shared.application.chat.ChatRealtimeBridge
import org.opencodemobile.shared.application.chat.ComposerController
import org.opencodemobile.shared.application.chat.TranscriptController
import org.opencodemobile.shared.application.interaction.PendingQuestionsController
import org.opencodemobile.shared.application.interaction.PendingQuestionsRealtimeBridge
import org.opencodemobile.shared.application.interaction.TurnAbortController
import org.opencodemobile.shared.application.notification.LocalNotificationCoordinator
import org.opencodemobile.shared.application.notification.NotificationPermissionNotifier
import org.opencodemobile.shared.application.permission.PermissionCoordinator
import org.opencodemobile.shared.application.permission.PermissionRealtimeBridge
import org.opencodemobile.shared.application.session.SessionListController
import org.opencodemobile.shared.application.session.SessionsScope
import org.opencodemobile.shared.data.cache.CacheStack
import org.opencodemobile.shared.data.chat.CacheComposerDraftStore
import org.opencodemobile.shared.data.permission.DeferredCachePendingPermissionStore
import org.opencodemobile.shared.domain.cache.ConnectionState
import org.opencodemobile.shared.domain.connection.ServerProfile
import org.opencodemobile.shared.domain.permission.BiometricAuthenticator
import org.opencodemobile.shared.networking.adapter.OpenCodeV2Adapter
import org.opencodemobile.shared.realtime.EventProcessor

/** Owns one verified connection; replacing it cancels all previous collectors. */
internal class IosSessionHost(
    private val adapter: OpenCodeV2Adapter,
    private val cache: CacheStack,
    private val biometric: BiometricAuthenticator,
    private val notifications: LocalNotificationCoordinator,
) {
    private val mutableRuntime = MutableStateFlow<IosSessionRuntime?>(null)
    val runtime: StateFlow<IosSessionRuntime?> = mutableRuntime
    private var foregrounded = false

    fun connect(profile: ServerProfile) {
        mutableRuntime.value?.close()
        mutableRuntime.value = IosSessionRuntime(profile, adapter, cache, biometric, notifications).also {
            it.permissions.onForegroundChanged(foregrounded)
        }
    }

    fun disconnect() {
        mutableRuntime.value?.close()
        mutableRuntime.value = null
        adapter.disconnect()
    }

    fun onForegroundChanged(active: Boolean) {
        foregrounded = active
        mutableRuntime.value?.permissions?.onForegroundChanged(active)
    }
}

/** Reuses the shared controllers and their offline, biometric and single-send gates. */
internal class IosSessionRuntime(
    profile: ServerProfile,
    adapter: OpenCodeV2Adapter,
    private val cache: CacheStack,
    biometric: BiometricAuthenticator,
    notifications: LocalNotificationCoordinator,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val processor = EventProcessor(adapter.eventTransport(profile.baseUrl))
    val connection = processor.state
    private val transcripts = TranscriptController(adapter)
    private val questionsController = PendingQuestionsController(adapter, cache.gate)
    private val abort = TurnAbortController(adapter, cache.gate)
    private val composerController = ComposerController(
        adapter,
        CacheComposerDraftStore(cache, cache, profile.id, PROJECT_ID),
        cache.gate,
        turnBlocked = { questionsController.state.value.blocksTurn },
    )
    private val permissionGateway = adapter.permissionGateway(profile.baseUrl)
    private val permissionCoordinator = PermissionCoordinator(
        permissionGateway,
        DeferredCachePendingPermissionStore(cache, cache::writer, profile.id),
        cache.gate,
        biometric,
        NotificationPermissionNotifier(notifications),
    )
    val sessions = SessionsPresenter(
        SessionListController(adapter, cache, cache.gate, { SessionsScope(profile.id, PROJECT_ID) }),
        scope,
    )
    val transcript = TranscriptPresenter(transcripts, scope, abort)
    val composer = ComposerPresenter(composerController, scope)
    val questions = QuestionsPresenter(questionsController, scope)
    val permissions = PermissionsPresenter(permissionCoordinator, scope)

    init {
        // Bind the mutation gate before any collector can attempt a cache write.
        scope.launch {
            processor.state.map { it.phase }.distinctUntilChanged().collect { phase ->
                cache.gate.onConnectionStateChanged(phase.toConnectionState())
                sessions.refresh()
            }
        }
        scope.launch {
            CacheWritePipeline(processor, cache.writer(), cache.gate, CacheScope(profile.id, PROJECT_ID)).start(scope)
            permissionCoordinator.start()
        }
        ChatRealtimeBridge(processor, adapter, transcripts, abort).start(scope)
        PendingQuestionsRealtimeBridge(processor, questionsController).start(scope)
        PermissionRealtimeBridge(processor, permissionGateway, permissionCoordinator).start(scope)
        processor.start(scope)
    }

    fun close() {
        permissions.onForegroundChanged(false)
        processor.stop()
        scope.cancel()
        cache.gate.onConnectionStateChanged(ConnectionState.Offline)
    }
}

private const val PROJECT_ID = "default"
