package org.opencodemobile.shared.security.identity

import kotlin.concurrent.Volatile
import org.opencodemobile.shared.domain.connection.ServerFingerprint

/**
 * Mutable pin/observation channel shared between the TOFU coordinator and the
 * platform TLS engine (OkHttp `X509TrustManager` / `URLSession` challenge
 * delegate).
 *
 * The adapter sets the expected pin for the profile it is connecting to and
 * installs a presenter observer; the engine then enforces the pin on the
 * handshake itself, as required by `docs/ARCHITECTURE.md` §"Server identity
 * verification (T1)" (defense in depth in front of the credential gate).
 *
 * Reads happen on engine threads; writes happen on the connecting coroutine,
 * so both fields are [Volatile]. The setter is intentionally last-value-wins,
 * and clearing is always explicit.
 */
public class ServerIdentityPinController {

    @Volatile
    private var expected: ServerFingerprint? = null

    @Volatile
    private var observer: ((ServerFingerprint) -> Unit)? = null

    /** Installs the pin the engine must enforce, or null to only capture. */
    public fun setExpectedPin(fingerprint: ServerFingerprint?) {
        expected = fingerprint
    }

    /** The pin the engine must enforce, if any. */
    public fun expectedPin(): ServerFingerprint? = expected

    /** Registers the callback invoked with each fingerprint observed on the wire. */
    public fun observe(onPresented: ((ServerFingerprint) -> Unit)?) {
        observer = onPresented
    }

    /** Reports a fingerprint observed by the engine. */
    public fun reportPresented(fingerprint: ServerFingerprint) {
        observer?.invoke(fingerprint)
    }

    /** Clears the pin and the observer. */
    public fun reset() {
        expected = null
        observer = null
    }
}