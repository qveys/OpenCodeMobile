package org.opencodemobile.shared.testsupport

/**
 * Deterministic behaviours that [MockOpenCodeServer] can replay.
 *
 * The baseline (Cahier des charges v1.0 §10.2) requires at least health, session list
 * and a scripted SSE stream; the remaining entries cover every scenario the MVP
 * acceptance run (OPE-112 §13) and the L2/L3 test suites must be able to inject.
 *
 * The nine mandatory scenarios are [Default] (`normal`), [Streaming], [Disconnect],
 * [Reconnect], [PermissionRequest], [MalformedEvent], [UnsupportedVersion],
 * [SlowNetwork] and [LongTranscript]. [AuthenticationFailure] and [ServerError] are
 * kept from the original baseline. See `README.md` in this module for the exact
 * transport semantics each scenario models.
 */
public enum class MockOpenCodeScenario {
    /** The nominal case: every route succeeds with the pinned fixtures and `/event` streams the default script. */
    Default,

    /**
     * `/event` streams the default script one event at a time, with a configurable delay
     * between events. The first event is readable before the last one is written, so a test
     * can prove the body is consumed progressively instead of arriving as one block.
     */
    Streaming,

    /**
     * `/event` stops the response body after [MockOpenCodeStreamConfig.disconnectAfterEvents]
     * events, before the script is exhausted. The client observes a truncated stream and must
     * reconnect and reconcile (see `README.md`). With
     * [MockOpenCodeStreamConfig.disconnectConnections] > 1 the first N connections are
     * truncated before the script resumes, which models a server that stays down.
     */
    Disconnect,

    /**
     * The first [MockOpenCodeStreamConfig.disconnectConnections] `/event` connections are
     * truncated like [Disconnect]; the next connection resumes the script where it stopped.
     * Already-delivered events are never replayed unless
     * [MockOpenCodeStreamConfig.replayFromEventId] asks for an overlapping replay.
     */
    Reconnect,

    /**
     * `/event` emits a `permission.asked` (and a `question.asked`) event, and the
     * `/permission` and `/question` routes accept the exact user decision.
     */
    PermissionRequest,

    /** `/event` emits one event whose `data:` is invalid JSON. */
    MalformedEvent,

    /** `GET /global/health` reports an incompatible version so handshake gating can be tested. */
    UnsupportedVersion,

    /** `/event` streams the default script with a configurable, assertable inter-event delay. */
    SlowNetwork,

    /**
     * `/event` streams a large fixture (hundreds of `message.part.updated` events).
     */
    LongTranscript,

    /**
     * `GET /provider` and `GET /agent` answer with empty lists, and `GET /question`
     * answers `[]`: a server that exposes no catalog and no pending interaction.
     * Used by V1-09 to prove the screen stays empty with a reason instead of
     * falling back to a hard-coded catalog.
     */
    NoCatalog,

    /**
     * A server whose published surface (`GET /doc`) does not include
     * `POST /session/{sessionID}/fork`, and whose fork route answers 404.
     * Used by V1-04 to prove the fork action is disabled from the server
     * surface with no rejected call, instead of assuming a hard-coded catalog.
     */
    NoFork,

    /** Every route returns `401 Unauthorized`, regardless of headers. */
    AuthenticationFailure,

    /** Every route returns `500 Internal Server Error` with a typed error body. */
    ServerError,
}
