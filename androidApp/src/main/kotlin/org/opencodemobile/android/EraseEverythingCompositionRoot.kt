package org.opencodemobile.android

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module
import org.opencodemobile.shared.application.erasure.EraseEverythingCoordinator
import org.opencodemobile.shared.domain.cache.CacheKeyStore
import org.opencodemobile.shared.domain.cache.LocalCacheEraser
import org.opencodemobile.shared.domain.permission.BiometricAuthenticator
import org.opencodemobile.shared.persistence.cache.CacheDatabase
import org.opencodemobile.shared.persistence.cache.FileLocalCacheEraser
import org.opencodemobile.shared.persistence.cache.SqlCipherCacheDriverProvider
import org.opencodemobile.shared.security.biometric.AndroidBiometricAuthenticator
import org.opencodemobile.shared.security.cache.AndroidKeystoreCacheKeyStore
import org.opencodemobile.shared.security.store.SecureServerCredentialStore
import org.opencodemobile.shared.security.store.SecureServerProfileStore

/**
 * Android composition root for "Tout effacer" (OPE-275 / ADR 0009).
 *
 * It is app-shell code, so it is the only place that may assemble the
 * persistence + security primitives behind the domain ports the coordinator
 * consumes. The profile and credential stores are the same singletons the
 * connection root bound; the cache eraser composes [CacheDatabase] (database +
 * `-wal`/`-shm`/`-journal`) with [AndroidKeystoreCacheKeyStore] (key material).
 *
 * The cache [CacheDatabase] is created here even though no read/write stack is
 * composed in this build: the erase path only closes and deletes, and using the
 * same database name and key store guarantees it removes the real artifacts.
 */
public val eraseEverythingCompositionModule: Module = module {
    single<BiometricAuthenticator> { AndroidBiometricAuthenticator(androidContext()) }
    single<CacheKeyStore> { AndroidKeystoreCacheKeyStore(androidContext()) }
    single { CacheDatabase(SqlCipherCacheDriverProvider(androidContext(), get())) }
    single<LocalCacheEraser> { FileLocalCacheEraser(get(), get()) }

    // The settings presenter runs the erase off the UI thread; Main.immediate
    // keeps the result state observable on the main dispatcher.
    single { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }

    single {
        EraseEverythingCoordinator(
            profileStore = get<SecureServerProfileStore>(),
            credentialStore = get<SecureServerCredentialStore>(),
            identityStore = get(),
            cacheEraser = get(),
            notifications = get(),
        )
    }
}
