package org.opencodemobile.android.di

import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import org.opencodemobile.shared.data.cache.CacheStack
import org.opencodemobile.shared.domain.cache.MutationGate
import org.opencodemobile.shared.domain.cache.SessionCache
import org.opencodemobile.shared.persistence.cache.SqlCipherCacheDriverProvider
import org.opencodemobile.shared.security.cache.AndroidKeystoreCacheKeyStore

/**
 * The cache stack, wired into the app composition root (OPE-172 / C8-F11).
 *
 * Before this module the whole cache — [CacheStack], [SqlCipherCacheDriverProvider]
 * and [AndroidKeystoreCacheKeyStore] — was built only in tests, so the at-rest
 * encryption control (T3) and the D8 gate guarded a library, not the app.
 *
 * Consumers resolve ports, never the stack's internals:
 * - [SessionCache] for reads (offline, read-only), and
 * - [MutationGate] for the shared [CacheStack.gate]; writes go through
 *   `CacheStack.writer()`, which is already wrapped in the D8 gate.
 */
public val cacheModule = module {
    single { AndroidKeystoreCacheKeyStore(androidContext()) }
    single { SqlCipherCacheDriverProvider(androidContext(), get()) }
    single { CacheStack(get()) }
    single<SessionCache> { get<CacheStack>() }
    single<MutationGate> { get<CacheStack>().gate }
}
