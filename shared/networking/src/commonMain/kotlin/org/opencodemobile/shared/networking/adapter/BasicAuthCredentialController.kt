package org.opencodemobile.shared.networking.adapter

import kotlin.concurrent.Volatile
import org.opencodemobile.shared.domain.connection.BasicAuthCredential

/**
 * Mutable holder for the Traefik/Dokploy reverse-proxy Basic Auth credential,
 * shared between [OpenCodeV2Adapter.connect] (which sets it only after the T1
 * identity check passes, see `docs/ARCHITECTURE.md` §"Server identity
 * verification (T1)") and the Ktor request pipeline installed by
 * [installBasicAuthHeader] (which reads it on every outgoing request).
 *
 * This credential authenticates against the reverse-proxy gate sitting in
 * front of the OpenCode server, not the server itself; it is unrelated to the
 * server's own bearer-token credential, which the generated client attaches
 * through its own `authTokenProvider`.
 *
 * Reads happen on engine/request threads; writes happen on the connecting
 * coroutine, so the field is [Volatile], mirroring [org.opencodemobile.shared.security.identity.ServerIdentityPinController].
 */
public class BasicAuthCredentialController {

    @Volatile
    private var credential: BasicAuthCredential? = null

    /** Installs the credential the request pipeline may attach, or null to detach it. */
    public fun setCredential(credential: BasicAuthCredential?) {
        this.credential = credential
    }

    /** The credential the request pipeline may attach, if any. */
    public fun currentCredential(): BasicAuthCredential? = credential

    /** Clears the credential; called from [OpenCodeV2Adapter.disconnect]. */
    public fun reset() {
        credential = null
    }
}
