# `shared:test-support`

Deterministic test fixtures for the OpenCode Mobile MVP. The module is consumed from
test source sets only.

## `MockOpenCodeServer`

An in-process fake of the OpenCode Server v2 HTTP surface. It opens no socket and every
response is built from the pinned `OpenCodeFixtures`, so runs are byte-stable across
platforms.

```kotlin
val server = MockOpenCodeServer(MockOpenCodeScenario.Streaming).start()
try {
    val events = server.client
        .get("${server.baseUrl}${MockOpenCodeServer.EVENT_PATH}") { skipSavingBody() }
        .bodyAsChannel()
    // read events one at a time
} finally {
    server.stop()
}
```

`server.client` is a real Ktor `HttpClient` (routing, headers, content negotiation and
body decoding all run) bound to a small custom engine instead of `MockEngine`.

### Why not `MockEngine`

`MockEngine` replies at the request/response grain: the previous `respondEventStream()`
returned **one** `HttpResponseData` whose body was the concatenation of every event
(`MockSseCodec.encode(events)`). There was no point of observation or interruption while
the body was read, so `streaming`, `slow-network`, `disconnect` and `reconnect` could not
be expressed, and `permission-request` had no data at all.

`MockOpenCodeServer` therefore uses a custom `HttpClientEngineBase` that writes the
`/event` body into a `ByteWriteChannel`:

- one `writeStringUtf8(event.encode())` + `flush()` per event, so the body is readable
  progressively;
- an optional `delay()` between events (`streaming`, `slow-network`);
- a configurable early end of the body (`disconnect`, `reconnect`).

Request/response routes share the same engine, so every fixture comes from one place and
`ktor-client-mock` is no longer a dependency of this module.

## Scenarios

`MockOpenCodeScenario` enumerates the nine mandatory MVP scenarios plus the original
baseline failure cases. All are injectable via the `MockOpenCodeServer(scenario)` constructor;
timings, stream shapes and fixture sizes are injectable via `MockOpenCodeStreamConfig`.

| Scenario | `/event` behaviour | Routes |
| --- | --- | --- |
| `Default` (`normal`) | streams `defaultSseEvents()` and ends | all nominal routes |
| `Streaming` | streams `streamingSseEvents()` one event per flush, `streamingDelayMillis` between events | all nominal routes |
| `Disconnect` | streams only the first `disconnectAfterEvents` events, then ends the body; repeats for `disconnectConnections` connections | all nominal routes |
| `Reconnect` | first `disconnectConnections` connections truncated like `Disconnect`; the next connection resumes the script | all nominal routes |
| `PermissionRequest` | streams `session.updated`, `permission.asked`, `question.asked` | `GET /permission`, `POST /permission/{id}/reply`, `GET /question`, `POST /question/{id}/reply`, `POST /question/{id}/reject` |
| `MalformedEvent` | one valid event then one truncated `data:` payload | nominal routes |
| `UnsupportedVersion` | normal stream | `GET /global/health` reports an old version |
| `SlowNetwork` | streams `slowNetworkSseEvents()` with `slowNetworkDelayMillis` between events | all nominal routes |
| `LongTranscript` | streams `longTranscriptSseEvents(longTranscriptPartCount)` | all nominal routes |
| `Abort` | streams `abortSseEvents()`, suspends after `abortHoldAfterEvents` events until `POST /session/{id}/abort`, then delivers the in-flight event and stops | `POST /session/{id}/abort` is recorded (with `directory`); the aborted turn is never replayed and is not committed to `GET /session/{id}/message` |
| `NoCatalog` | normal stream | `GET /provider`, `GET /agent` and `GET /question` answer with empty payloads (V1-09 empty-state) |
| `NoCatalogRoutes` | normal stream | `GET /provider` and `GET /agent` answer `404` — the routes do not exist (V1-09 unsupported-state) |
| `CatalogUnavailable` | normal stream | `GET /provider` and `GET /agent` answer `503`; every other route succeeds (V1-09 transient-error-state) |
| `NoFork` | normal stream | `GET /doc` omits `POST /session/{sessionID}/fork`, and that route answers `404` (V1-04 capability detection) |
| `AuthenticationFailure` | – | every route returns `401` |
| `ServerError` | – | every route returns `500` with a typed body |

`MockOpenCodeStreamConfig` also carries the OPE-131 coverage knobs used by the
kill → polling → resume line: `disconnectConnections`, `replayFromEventId`, `tailEvents`,
`tailDelayMillis` and `keepOpenMillis`. Each is exercised by a dedicated test in the module.

### Session CRUD and the published surface (V1-04)

The mock mutates real state so the session routes behave like a server rather than a
fixed fixture:

- `POST /session` honours the request `title`/`directory` and appends the session;
- `PATCH /session/{id}` renames it and the change is visible on the next `GET /session`;
- `DELETE /session/{id}` removes it from the list;
- `POST /session/{id}/fork` creates a child carrying `parentID`.

`GET /doc` returns the server's published OpenAPI document. Capability detection reads
this surface: the `normal` scenario advertises the fork path, while `NoFork` omits it and
answers `404` on the fork route, so the app can disable the action without sending a call
the server would reject.

## Disconnect / reconnect semantics (consistency with OPE-106)

The mock models a dropped SSE connection as an **early end of the response body**: after
`disconnectAfterEvents` events the body simply stops, which is what the client observes when
an SSE connection is lost. The client is expected to reconnect and reconcile, exactly as
`docs/ARCHITECTURE.md` §3.2 and OPE-106 require:

```
on stream loss:
   degrade to polling GET /session/status
   exponential backoff with a floor on the interval
   on reconnect: fetch snapshot → reconcile →
                 drop replayed/duplicate events → resume live
```

The mock's event script advances monotonically across `/event` connections (a live
broadcast does not replay history to a new subscriber), so a reconnecting client normally
receives only events that occur after it reconnects. The test
`reconnectScenarioResumesWithoutReplayingConsumedEvents` proves the no-replay baseline. The
mock does **not** invent a resumption parameter on `/event`: `/event` in the pinned spec has
no resume parameter, and reconciliation against the snapshot is the client's job.

### Staying down vs. resuming

`disconnectConnections` (default `1`) selects how many consecutive `/event` connections are
truncated before the script resumes. Set it above `1` with `disconnectAfterEvents = 0` to model
a server that stays down: every attempt returns an empty body, then the connection after the
last one delivers the full script.
`stayingDownServerFailsConsecutiveAttemptsBeforeResuming` asserts N empty attempts followed by
the resumed script, which is what the backoff-with-a-floor requirement needs.

### Replay and dedup by server id

A live server may replay the backlog when a client resubscribes. `replayFromEventId`, when set,
makes a reconnecting client receive the events from that id up to the current cursor again
before the new events. `replayedEventIdIsDeliveredAgainForDeduplication` shows the same id
arriving twice and asserts that deduping by server-issued id yields the script exactly once —
the `drop replayed/duplicate events` line of §3.2 (OPE-106 `EventProcessor`).

### Resume live (the body stays open)

By default the body ends as soon as the script is exhausted, so a client cannot distinguish a
healthy but idle server from a server stuck in a reconnect loop. `tailEvents` are written after
the script (after `tailDelayMillis`) on the connection that exhausts it, and `keepOpenMillis`
keeps the body open afterwards.
`reconnectingClientReceivesTailEventsOnANonClosingConnection` proves a reconnect drains the
remaining backlog and then receives an event emitted later on the still-open channel;
`keepOpenMillisKeepsTheEventBodyOpenAfterTheScript` proves the body does not end early.

### Abort: the turn stops and is never replayed (V1-08)

`Abort` models a user abort mid-turn. `/event` delivers the prefix (`session.updated` plus
the first `abortHoldAfterEvents - 1` parts), then **suspends** until `POST /session/{id}/abort`
lands. It resumes by delivering the single event already in flight (the client must drop it),
and stops: nothing after it is emitted and the aborted turn is filtered out of any later
connection, so a reconnecting client never replays it.

`POST /session/{id}/abort` records the request (with its optional `directory` query
parameter, ADR-0002 §3.3) and marks the session. `GET /session/{id}/message` only ever
contains the parts actually delivered before the abort, so the client can rebuild the
authoritative transcript from the next snapshot. `server.aborts`, `server.abortedSessions`
and `server.deliveredParts` expose the state for assertions.

### Session status fixture

`GET /session/status` returns `OpenCodeFixtures.sessionStatusJson()`: a non-empty
`Map<String, ApiSessionStatus>` keyed by session id (the first session reports `retry` with
`attempt`/`message`/`next`, the rest `idle`). `MockOpenCodeServerAdapterTest` decodes it through
the generated `OpenCodeApiClient`, so fallback polling has a reconciliation fixture.

No transport exception is simulated: at the SSE layer a server-side close and a network
reset both terminate the body, and the `EventProcessor` contract reacts to stream loss the
same way. Tests that need to observe the cut read the body as a channel and assert the
stream ends before the last scripted event. A `disconnectMode: EndOfBody | ChannelFailure`
knob that would surface a socket reset as an `IOException` on the read side is deliberately
left out; it is the one optional item of OPE-131 that is not covered.

## Permission / question fixtures

`GET /permission` and `GET /question` return the pinned pending decision shapes from
`OpenCodeFixtures`, and the reply/reject routes accept the exact user decision (the body is
parsed and captured in `server.permissionReplies` / `server.questionReplies`, including the
question `answers`). This unblocks the V1-06 permission flow (OPE-110) and the V1-07
pending-question flow (OPE-111).

## Model / agent fixtures (V1-09)

`GET /provider` returns the pinned spec object (`all` providers, each with a model map keyed
by model id, plus `default` and `connected`), and `GET /agent` returns the pinned `Agent`
list. `MockOpenCodeScenario.NoCatalog` makes both routes (and `GET /question`) answer with
empty payloads, so a client can prove it renders an empty list with a reason instead of a
hard-coded catalog. The `OpenCodeV2Adapter` interaction integration test
(`InteractionGatewayAdapterTest`) drives these routes end to end (OPE-111).
