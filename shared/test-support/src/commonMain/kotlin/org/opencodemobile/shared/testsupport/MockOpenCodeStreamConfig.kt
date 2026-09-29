package org.opencodemobile.shared.testsupport

/**
 * Injectable knobs for the `/event` transport modelled by [MockOpenCodeServer].
 *
 * Defaults are chosen so every scenario is deterministic and fast enough for CI. Tests that
 * need to assert a cadence or a shorter/longer transcript pass a custom instance to
 * [MockOpenCodeServer].
 */
public data class MockOpenCodeStreamConfig(
    /** Inter-event delay used by [MockOpenCodeScenario.Streaming] (milliseconds). */
    public val streamingDelayMillis: Long = 120L,

    /** Inter-event delay used by [MockOpenCodeScenario.SlowNetwork] (milliseconds). */
    public val slowNetworkDelayMillis: Long = 150L,

    /**
     * Number of events each truncated `/event` connection delivers before
     * [MockOpenCodeScenario.Disconnect] and [MockOpenCodeScenario.Reconnect] cut the body.
     *
     * `0` models a connection that drops immediately, which is the shape of a server that is
     * still down: the client observes an empty body and must retry.
     */
    public val disconnectAfterEvents: Int = 2,

    /**
     * Number of consecutive `/event` connections that are truncated before the script
     * resumes. The default `1` preserves the historical single-cut behaviour.
     *
     * Raise it (with [disconnectAfterEvents] = `0`) to model a server that stays down for N
     * failed attempts before it answers again, which is what the exponential-backoff-with-a-
     * floor line of `docs/ARCHITECTURE.md` §3.2 needs. The connection after the last
     * truncated one delivers the remaining script normally.
     */
    public val disconnectConnections: Int = 1,

    /**
     * When non-null, a reconnecting `/event` client receives the tail of the events it already
     * consumed — from this server-issued id up to the current cursor — before the new events.
     *
     * A live server can replay the backlog on resubscribe, so the client must drop an id it
     * already consumed (OPE-106 `EventProcessor` dedup by server id). With this knob a test can
     * assert that the same id is delivered twice and that a dedup filter keeps a single copy.
     */
    public val replayFromEventId: String? = null,

    /**
     * Events written after the main script on the connection that exhausts it. Combined with
     * [tailDelayMillis] they model activity that arrives **later** on a connection that stays
     * open ("resume live" in `docs/ARCHITECTURE.md` §3.2), instead of the body ending as soon
     * as the script is empty.
     */
    public val tailEvents: List<MockSseEvent> = emptyList(),

    /** Delay before [tailEvents] are written, in milliseconds. `0` means immediately. */
    public val tailDelayMillis: Long = 0L,

    /**
     * How long the `/event` connection stays open after the script (and any [tailEvents]) have
     * been written, before the body ends. `0` closes as soon as the script is done, i.e. a
     * healthy but idle server and a server in a reconnect loop are indistinguishable. A value
     * above `0` keeps the body open so a test can prove the channel did not end.
     */
    public val keepOpenMillis: Long = 0L,

    /** Number of `message.part.updated` events in the [MockOpenCodeScenario.LongTranscript] fixture. */
    public val longTranscriptPartCount: Int = 600,
)
