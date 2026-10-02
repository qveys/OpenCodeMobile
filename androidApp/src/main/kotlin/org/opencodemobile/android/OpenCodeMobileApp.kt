package org.opencodemobile.android

import android.app.Application
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.opencodemobile.features.connection.ConnectionModule

class OpenCodeMobileApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@OpenCodeMobileApp)
            // Per-module Koin modules (shared/*, features/*) are added here as each
            // layer is implemented; D12 keeps shared/domain free of Koin entirely.
            // The camera port stays out of Koin: it is activity-scoped and is
            // created directly by MainActivity (see ConnectionCompositionRoot).
            modules(connectionCompositionModule, ConnectionModule.koinModule)
        }
    }
}
