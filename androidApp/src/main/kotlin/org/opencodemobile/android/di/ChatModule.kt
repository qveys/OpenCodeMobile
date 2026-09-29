package org.opencodemobile.android.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.opencodemobile.android.chat.ChatConnection
import org.opencodemobile.android.chat.ChatRuntime
import org.opencodemobile.android.chat.DeferredChatEventDecoder
import org.opencodemobile.android.chat.DeferredChatGateway
import org.opencodemobile.features.composer.ComposerPresenter
import org.opencodemobile.features.transcript.TranscriptPresenter
import org.opencodemobile.shared.application.chat.ComposerController
import org.opencodemobile.shared.application.chat.InMemoryComposerDraftStore
import org.opencodemobile.shared.application.chat.TranscriptController
import org.opencodemobile.shared.data.cache.CacheStack
import org.opencodemobile.shared.data.chat.CacheComposerDraftStore
import org.opencodemobile.shared.domain.cache.MutationGate
import org.opencodemobile.shared.domain.cache.SessionCache
import org.opencodemobile.shared.domain.chat.ChatEventDecoder
import org.opencodemobile.shared.domain.chat.ComposerDraftStore
import org.opencodemobile.shared.domain.chat.OpenCodeChatGateway

/** Koin qualifier for the app-wide scope the chat presenters live in. */
public const val CHAT_SCOPE_QUALIFIER: String = "chatScope"

/**
 * The V1-05 chat surface, wired into the app shell.
 *
 * It assembles the real controller/presenter graph:
 *
 * - `gateway`/`decoder` are the real `OpenCodeV2Adapter` chat surface, resolved
 *   per call through the optional [ChatConnection] seam (fail-closed until the
 *   connection root binds one),
 * - the draft is stored in the encrypted cache when a connection scope exists,
 *   and in memory otherwise; restoring it never sends a prompt (§8.1),
 * - the D8 [MutationGate] comes from the cache composition root, so a send is
 *   refused offline before touching the wire,
 * - the controllers own every gate; the presenters only forward intents.
 */
public val chatModule: Module = module {
    single<CoroutineScope>(named(CHAT_SCOPE_QUALIFIER)) {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    single<OpenCodeChatGateway> {
        DeferredChatGateway { getOrNull<ChatConnection>() }
    }

    single<ChatEventDecoder> {
        DeferredChatEventDecoder { getOrNull<ChatConnection>() }
    }

    single<ComposerDraftStore> {
        val connection = getOrNull<ChatConnection>()
        val cache = getOrNull<SessionCache>()
        val cacheStack = getOrNull<CacheStack>()
        if (connection != null && cache != null && cacheStack != null) {
            CacheComposerDraftStore(
                cache = cache,
                cacheStack = cacheStack,
                serverId = connection.serverId,
                projectId = connection.projectId,
            )
        } else {
            InMemoryComposerDraftStore()
        }
    }

    single { TranscriptController(get<OpenCodeChatGateway>()) }

    single {
        ComposerController(
            gateway = get<OpenCodeChatGateway>(),
            drafts = get<ComposerDraftStore>(),
            mutationGate = get<MutationGate>(),
        )
    }

    single { TranscriptPresenter(get(), get(named(CHAT_SCOPE_QUALIFIER))) }
    single { ComposerPresenter(get(), get(named(CHAT_SCOPE_QUALIFIER))) }

    single { ChatRuntime(get(), { getOrNull<ChatConnection>() }) }
}
