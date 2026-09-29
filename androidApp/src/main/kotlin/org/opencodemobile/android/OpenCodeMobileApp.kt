package org.opencodemobile.android

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.qualifier.named
import org.opencodemobile.android.cache.CacheWriteRuntime
import org.opencodemobile.android.chat.ChatRuntime
import org.opencodemobile.android.di.CHAT_SCOPE_QUALIFIER
import org.opencodemobile.android.di.PERMISSION_SCOPE_QUALIFIER
import org.opencodemobile.android.di.cacheModule
import org.opencodemobile.android.di.chatModule
import org.opencodemobile.android.di.permissionModule
import org.opencodemobile.android.di.sessionsModule
import org.opencodemobile.android.permission.PermissionRuntime

class OpenCodeMobileApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@OpenCodeMobileApp)
            // Per-module Koin modules (shared/*, features/*) are added here as each
            // layer is implemented; D12 keeps shared/domain free of Koin entirely.
            modules(cacheModule, permissionModule, sessionsModule, chatModule)
        }
        startPermissionSurface()
        startCacheWritePath()
        startChatSurface()
    }

    /**
     * Starts the V1-05 chat realtime bridge: assistant `message.*` events feed
     * the open transcript, and every transition into `Live` reconciles it from
     * the server. It is inert until a connection composition root binds a
     * `ChatConnection`; that root calls `ChatRuntime.start()` again once it has.
     */
    private fun startChatSurface() {
        val koin = GlobalContext.getOrNull() ?: return
        val runtime = runCatching { koin.get<ChatRuntime>() }.getOrNull() ?: return
        val scope = runCatching {
            koin.get<CoroutineScope>(named(CHAT_SCOPE_QUALIFIER))
        }.getOrNull() ?: return
        runtime.start(scope)
    }

    /**
     * Starts the V1-06 permission surface: it restores the persisted pending set
     * (a killed app brings the banner back with no implicit approval) and, once a
     * connection is wired, feeds `permission.asked` / `permission.replied` into the
     * coordinator. It never approves anything by itself.
     *
     * Resolution is defensive while the connection composition root does not exist
     * yet: an unwired graph leaves the surface inert instead of crashing at launch.
     */
    private fun startPermissionSurface() {
        val koin = GlobalContext.getOrNull() ?: return
        val runtime = runCatching { koin.get<PermissionRuntime>() }.getOrNull() ?: return
        val scope = runCatching {
            koin.get<CoroutineScope>(named(PERMISSION_SCOPE_QUALIFIER))
        }.getOrNull() ?: return
        runtime.start(scope)
    }

    /**
     * Starts the D8 cache write path (OPE-180): it binds the connection's
     * realtime pipeline to the cache gate and projects snapshots/events through
     * the gated writer. It is inert until a connection composition root binds a
     * `CacheConnection`; that root calls `CacheWriteRuntime.start()` again once
     * it has bound one.
     */
    private fun startCacheWritePath() {
        val koin = GlobalContext.getOrNull() ?: return
        val runtime = runCatching { koin.get<CacheWriteRuntime>() }.getOrNull() ?: return
        runtime.start()
    }
}
