package org.opencodemobile.shared.domain.cache

/**
 * Default [MutationGate]: mutations are offered only while the app has a live
 * server connection (D8, `docs/ARCHITECTURE.md` §3.4).
 *
 * The cache write pipeline (`CacheWritePipeline`, in `shared/application`)
 * calls [onConnectionStateChanged] as the server connection comes and goes;
 * every mutation surface reads [mutationsAllowed]. Offline, mutation affordances
 * stay disabled and nothing is queued: the local cache is only ever written from
 * server events and snapshots.
 *
 * It lives next to the [MutationGate] port and [ConnectionState] so the cache
 * composition on either platform can construct it without reaching into the
 * application layer.
 */
public class ConnectivityMutationGate(
    initialState: ConnectionState = ConnectionState.Offline,
) : MutationGate {

    private var state: ConnectionState = initialState

    public fun onConnectionStateChanged(newState: ConnectionState) {
        state = newState
    }

    public fun connectionState(): ConnectionState = state

    override fun mutationsAllowed(): Boolean = state == ConnectionState.Online
}
