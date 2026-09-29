package org.opencodemobile.shared.testsupport

/**
 * Deterministic behaviours that [MockOpenCodeServer] can replay.
 *
 * The baseline (Cahier des charges v1.0 §10.2) requires at least health, session list
 * and a scripted SSE stream; the remaining entries cover scenarios named in §10.2
 * (`auth-failure`, `malformed-event`, `server-error`, `unsupported-version`) so
 * later L1/L2 tests can exercise failure and recovery paths without a real server.
 */
public enum class MockOpenCodeScenario {
    /** Every route succeeds with the pinned fixtures. */
    Default,

    /** `GET /global/health` reports an incompatible version so handshake gating can be tested. */
    UnsupportedVersion,

    /** Every route returns `401 Unauthorized`, regardless of headers. */
    AuthenticationFailure,

    /** `GET /event` emits one event whose `data:` is invalid JSON. */
    MalformedEvent,

    /** Every route returns `500 Internal Server Error` with a typed error body. */
    ServerError,
}