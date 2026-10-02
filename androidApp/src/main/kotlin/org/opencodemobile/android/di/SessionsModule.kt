package org.opencodemobile.android.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.opencodemobile.android.connection.ConnectionBinder
import org.opencodemobile.android.session.DeferredSessionGateway
import org.opencodemobile.android.session.SessionConnection
import org.opencodemobile.features.sessions.SessionsPresenter
import org.opencodemobile.shared.application.session.SessionListController
import org.opencodemobile.shared.application.session.SessionsScope
import org.opencodemobile.shared.domain.cache.MutationGate
import org.opencodemobile.shared.domain.cache.SessionCache
import org.opencodemobile.shared.domain.session.SessionGateway

/** Koin qualifier for the app-wide scope the sessions presenter lives in. */
public const val SESSIONS_SCOPE_QUALIFIER: String = "sessionsScope"

/**
 * The V1-04 session surface, wired into the app shell.
 *
 * It assembles the real controller/presenter graph:
 *
 * - `gateway` is the real `OpenCodeV2Adapter` session surface, resolved per call
 *   through the optional [SessionConnection] seam (fail-closed until the
 *   connection root binds one),
 * - `cache` and the D8 [MutationGate] come from the cache composition root
 *   (`cacheModule`), so the list reads the encrypted cache offline and no
 *   mutation is possible there,
 * - the controller owns every gate; the presenter only forwards intents.
 */
public val sessionsModule: Module = module {
    single<CoroutineScope>(named(SESSIONS_SCOPE_QUALIFIER)) {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    single<SessionGateway> {
        DeferredSessionGateway { getOrNull<ConnectionBinder>()?.session() }
    }

    single {
        SessionListController(
            gateway = get<SessionGateway>(),
            cache = get<SessionCache>(),
            gate = get<MutationGate>(),
            scope = {
                getOrNull<ConnectionBinder>()?.session()?.let { connection ->
                    SessionsScope(serverId = connection.serverId, projectId = connection.projectId)
                }
            },
        )
    }

    single { SessionsPresenter(get(), get(named(SESSIONS_SCOPE_QUALIFIER))) }
}
