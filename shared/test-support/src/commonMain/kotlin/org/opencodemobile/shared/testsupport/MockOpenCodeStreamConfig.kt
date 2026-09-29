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
     * Number of events the first `/event` connection delivers before
     * [MockOpenCodeScenario.Disconnect] and [MockOpenCodeScenario.Reconnect] cut the body.
     */
    public val disconnectAfterEvents: Int = 2,

    /** Number of `message.part.updated` events in the [MockOpenCodeScenario.LongTranscript] fixture. */
    public val longTranscriptPartCount: Int = 600,
)
