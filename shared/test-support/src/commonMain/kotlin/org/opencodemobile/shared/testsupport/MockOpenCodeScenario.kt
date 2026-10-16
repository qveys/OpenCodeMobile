package org.opencodemobile.shared.testsupport

/** Deterministic HTTP and volatile SSE behaviours for the OpenCode 2.0.6 API. */
public enum class MockOpenCodeScenario {
    /** All implemented routes succeed with the pinned fixtures. */
    Default,
    /** Public events arrive progressively with the configured inter-event delay. */
    Streaming,
    /** The first configured connections end before the business script is exhausted. */
    Disconnect,
    /** Reconnect continues with fresh events, never replaying Last-Event-ID. */
    Reconnect,
    /** Pending permission and form are exposed through REST and their public events. */
    PermissionRequest,
    /** One data frame contains malformed JSON after a valid business event. */
    MalformedEvent,
    /** GET /api/info reports a version incompatible with the 2.x profile. */
    UnsupportedVersion,
    /** Public events use the configured slow-network delay. */
    SlowNetwork,
    /** The turn holds until POST /api/session/{id}/interrupt; in-flight content is not committed. */
    Abort,
    /** Hundreds of session.text.ended events and matching assistant transcript entries. */
    LongTranscript,
    /** Provider, model and agent data arrays are empty. */
    NoCatalog,
    /** Catalog routes return 404. */
    NoCatalogRoutes,
    /** Catalog routes return 503 while other endpoints succeed. */
    CatalogUnavailable,
    /** Fork returns 404, allowing callers to exercise a rejected request. */
    NoFork,
    /** Every request returns 401. */
    AuthenticationFailure,
    /** Every request returns 500. */
    ServerError,
}
