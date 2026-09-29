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
timings and fixture sizes are injectable via `MockOpenCodeStreamConfig`.

| Scenario | `/event` behaviour | Routes |
| --- | --- | --- |
| `Default` (`normal`) | streams `defaultSseEvents()` and ends | all nominal routes |
| `Streaming` | streams `streamingSseEvents()` one event per flush, `streamingDelayMillis` between events | all nominal routes |
| `Disconnect` | streams only the first `disconnectAfterEvents` events, then ends the body | all nominal routes |
| `Reconnect` | first connection truncated like `Disconnect`; the next connection resumes the script | all nominal routes |
| `PermissionRequest` | streams `session.updated`, `permission.asked`, `question.asked` | `GET /permission`, `POST /permission/{id}/reply`, `GET /question`, `POST /question/{id}/reply`, `POST /question/{id}/reject` |
| `MalformedEvent` | one valid event then one truncated `data:` payload | nominal routes |
| `UnsupportedVersion` | normal stream | `GET /global/health` reports an old version |
| `SlowNetwork` | streams `slowNetworkSseEvents()` with `slowNetworkDelayMillis` between events | all nominal routes |
| `LongTranscript` | streams `longTranscriptSseEvents(longTranscriptPartCount)` | all nominal routes |
| `AuthenticationFailure` | – | every route returns `401` |
| `ServerError` | – | every route returns `500` with a typed body |

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
broadcast does not replay history to a new subscriber), so a reconnecting client receives
only events that occur after it reconnects. Already-delivered events are never replayed, and
the test `reconnectScenarioResumesWithoutReplayingConsumedEvents` proves it. The mock does
**not** invent a resumption parameter on `/event`: `/event` in the pinned spec has no resume
parameter, and reconciliation against the snapshot is the client's job.

No transport exception is simulated: at the SSE layer a server-side close and a network
reset both terminate the body, and the `EventProcessor` contract reacts to stream loss the
same way. Tests that need to observe the cut read the body as a channel and assert the
stream ends before the last scripted event.

## Permission / question fixtures

`GET /permission` and `GET /question` return the pinned pending decision shapes from
`OpenCodeFixtures`, and the reply/reject routes accept the exact user decision (the body is
parsed and captured in `server.permissionReplies` / `server.questionReplies`). This unblocks
the V1-06 permission flow (OPE-110) and the V1-07 pending-question flow (OPE-111).
