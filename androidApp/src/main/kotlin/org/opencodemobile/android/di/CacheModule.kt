package org.opencodemobile.android.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.opencodemobile.android.cache.CacheConnection
import org.opencodemobile.android.cache.CacheWriteRuntime
import org.opencodemobile.shared.data.cache.CacheStack
import org.opencodemobile.shared.domain.cache.MutationGate
import org.opencodemobile.shared.domain.cache.SessionCache
import org.opencodemobile.shared.persistence.cache.SqlCipherCacheDriverProvider
import org.opencodemobile.shared.security.cache.AndroidKeystoreCacheKeyStore

/** Koin qualifier for the process scope the cache write path runs in. */
public const val CACHE_WRITE_SCOPE_QUALIFIER: String = "cacheWriteScope"

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
 *
 * [CacheWriteRuntime] is the positive half of the D8 gate (OPE-180): once a
 * connection binds [CacheConnection], it binds the realtime pipeline to the gate
 * and starts the write path. Until then it is inert, exactly like the permission
 * surface.
 */
public val cacheModule = module {
    single { AndroidKeystoreCacheKeyStore(androidContext()) }
    single { SqlCipherCacheDriverProvider(androidContext(), get()) }
    single { CacheStack(get()) }
    single<SessionCache> { get<CacheStack>() }
    single<MutationGate> { get<CacheStack>().gate }

    single<CoroutineScope>(named(CACHE_WRITE_SCOPE_QUALIFIER)) {
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    single {
        CacheWriteRuntime(
            scope = get(named(CACHE_WRITE_SCOPE_QUALIFIER)),
            resolveConnection = { getOrNull<CacheConnection>() },
            resolveStack = { getOrNull<CacheStack>() },
        )
    }
}
