package org.opencodemobile.features.settings

import org.koin.core.module.Module
import org.koin.dsl.module

// App/server settings. Must not depend on shared/networking, shared/persistence, shared/security, or another feature's internals (§5.2) -- only shared/domain, shared/application, and design-system.
public object SettingsModule {

    /**
     * Koin wiring for the settings feature. The composition root binds the
     * platform [org.opencodemobile.shared.domain.localaccess.LocalAccessSettingsStore];
     * this module only assembles the presenter that reads it.
     */
    public val koinModule: Module = module {
        factory { LocalAccessSettingsController(get()) }
    }
}
