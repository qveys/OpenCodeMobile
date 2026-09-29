package org.opencodemobile.android.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.opencodemobile.android.questions.DeferredInteractionGateway
import org.opencodemobile.android.questions.PendingQuestionsRuntime
import org.opencodemobile.android.questions.QuestionConnection
import org.opencodemobile.features.questions.QuestionsPresenter
import org.opencodemobile.shared.application.interaction.PendingQuestionsController
import org.opencodemobile.shared.application.interaction.PendingQuestionsRealtimeBridge
import org.opencodemobile.shared.domain.cache.MutationGate
import org.opencodemobile.shared.domain.interaction.OpenCodeInteractionGateway

/** Koin qualifier for the app-wide scope the question presenter lives in. */
public const val QUESTIONS_SCOPE_QUALIFIER: String = "questionsScope"

/**
 * The V1-07 pending-question surface, wired into the app shell (OPE-192).
 *
 * It assembles the real controller/presenter graph the recette found missing:
 *
 * - the `OpenCodeInteractionGateway` is the real `OpenCodeV2Adapter` interaction
 *   surface, resolved per call through the optional [QuestionConnection] seam
 *   (fail-closed until the connection root binds one),
 * - the D8 [MutationGate] comes from the cache composition root, so an answer or
 *   a reject is refused offline before touching the wire,
 * - [PendingQuestionsRuntime] starts the realtime bridge, which refreshes
 *   `GET /question` at start and on every return to `Live`, so a killed app comes
 *   back with the question still displayed,
 * - the controller owns every gate; the presenter only forwards intents.
 */
public val questionsModule: Module = module {
    single<CoroutineScope>(named(QUESTIONS_SCOPE_QUALIFIER)) {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    single<OpenCodeInteractionGateway> {
        DeferredInteractionGateway { getOrNull<QuestionConnection>() }
    }

    single { PendingQuestionsController(get<OpenCodeInteractionGateway>(), get<MutationGate>()) }

    single { QuestionsPresenter(get<PendingQuestionsController>(), get(named(QUESTIONS_SCOPE_QUALIFIER))) }

    single {
        PendingQuestionsRuntime(getOrNull<PendingQuestionsController>()) {
            val connection = getOrNull<QuestionConnection>()
            if (connection != null) {
                PendingQuestionsRealtimeBridge(
                    source = connection.source,
                    controller = get<PendingQuestionsController>(),
                    directory = connection.directory,
                )
            } else {
                null
            }
        }
    }
}
