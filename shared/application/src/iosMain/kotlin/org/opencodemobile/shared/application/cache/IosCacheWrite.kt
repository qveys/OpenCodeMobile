package org.opencodemobile.shared.application.cache

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.opencodemobile.shared.domain.cache.ConnectivityMutationGate
import org.opencodemobile.shared.domain.cache.SessionCacheWriter
import org.opencodemobile.shared.domain.event.EventSource

/**
 * Starts the D8 cache write path for the iOS host (OPE-180).
 *
 * The Swift composition root (`iosApp/iosApp/CacheComposition.swift`) builds the
 * stack with `createIosCacheStack()` and resolves the gated writer with
 * `stack.writer()`. This entry point owns the process-lifetime [CoroutineScope]
 * so the host never has to construct a Kotlin coroutine scope itself, and it
 * returns nothing coroutine-shaped to Swift.
 */
public fun startIosCacheWritePipeline(
    source: EventSource,
    writer: SessionCacheWriter,
    gate: ConnectivityMutationGate,
    serverId: String,
    projectId: String,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    CacheWritePipeline(
        source = source,
        writer = writer,
        gate = gate,
        scope = CacheScope(serverId = serverId, projectId = projectId),
    ).start(scope)
}
