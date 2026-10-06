package org.opencodemobile.android

import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module
import org.opencodemobile.shared.domain.localaccess.LocalAccessSettingsStore
import org.opencodemobile.shared.security.localaccess.AndroidLocalAccessSettingsStore

/**
 * Android composition root for the §7.3 local-access preferences.
 *
 * Like the connection composition root, this is app-shell code: it is the only
 * place that may see `shared/security` and bind its Android actual to the domain
 * port. The settings feature only depends on the domain port.
 */
public val localAccessCompositionModule: Module = module {
    single<LocalAccessSettingsStore> { AndroidLocalAccessSettingsStore(androidContext()) }
}
