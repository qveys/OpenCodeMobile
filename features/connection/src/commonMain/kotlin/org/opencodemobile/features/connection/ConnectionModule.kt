package org.opencodemobile.features.connection

import org.koin.core.module.Module
import org.koin.dsl.module
import org.opencodemobile.shared.application.connection.ServerConnectionSetup

// Server profile entry/import, handshake UI. Must not depend on shared/networking, shared/persistence, shared/security, or another feature's internals (§5.2) -- only shared/domain, shared/application, and design-system.
public object ConnectionModule {

    /**
     * Koin wiring for the connection feature. Declared here (not in a shared
     * module) per the §5.2 rule that DI modules live in the composition root or
     * a feature.
     *
     * The composition root provides the
     * [org.opencodemobile.shared.domain.connection.OpenCodeGateway] and the
     * platform [QrCodeScanner]; this module only assembles the feature's use
     * case.
     */
    public val koinModule: Module = module {
        factory { ServerConnectionSetup(get()) }
    }
}
