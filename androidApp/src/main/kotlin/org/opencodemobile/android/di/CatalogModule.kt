package org.opencodemobile.android.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.opencodemobile.features.catalog.CatalogPresenter
import org.opencodemobile.shared.application.interaction.ServerCatalogController
import org.opencodemobile.shared.domain.interaction.OpenCodeInteractionGateway

/** Koin qualifier for the app-wide scope the catalog presenter lives in. */
public const val CATALOG_SCOPE_QUALIFIER: String = "catalogScope"

/**
 * The V1-09 model/agent surface, wired into the app shell (OPE-194).
 *
 * It assembles the controller/presenter graph the recette found missing:
 *
 * - the `OpenCodeInteractionGateway` is the **already-bound** real
 *   `OpenCodeV2Adapter` interaction surface (declared once by `questionsModule`
 *   through the optional `QuestionConnection` seam); this module does not declare
 *   a second binding,
 * - [ServerCatalogController] reads `GET /provider` and `GET /agent` and keeps the
 *   server's answer verbatim — it never falls back to a built-in catalog,
 * - the presenter owns no gate; it only maps state and forwards the refresh.
 */
public val catalogModule: Module = module {
    single<CoroutineScope>(named(CATALOG_SCOPE_QUALIFIER)) {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    single { ServerCatalogController(get<OpenCodeInteractionGateway>()) }

    single { CatalogPresenter(get<ServerCatalogController>(), get(named(CATALOG_SCOPE_QUALIFIER))) }
}
