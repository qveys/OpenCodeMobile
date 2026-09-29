package org.opencodemobile.shared.data.cache

import org.opencodemobile.shared.domain.cache.ConnectionState
import org.opencodemobile.shared.persistence.cache.DataProtectionCacheDriverProvider

/**
 * Builds the iOS cache stack (OPE-172).
 *
 * iOS relies on OS Data Protection plus backup exclusion instead of an
 * app-managed passphrase (`docs/ARCHITECTURE.md` §"Local cache encryption at
 * rest"), so there is no `CacheKeyStore` here: the driver provider already
 * creates its files inside the protected, backup-excluded directory.
 *
 * The Swift host calls this from its composition root and starts the
 * `RealtimeCacheProjector` with `stack.writer()`.
 */
public fun createIosCacheStack(
    initialConnectionState: ConnectionState = ConnectionState.Offline,
): CacheStack = CacheStack(
    driverProvider = DataProtectionCacheDriverProvider(),
    initialConnectionState = initialConnectionState,
)
