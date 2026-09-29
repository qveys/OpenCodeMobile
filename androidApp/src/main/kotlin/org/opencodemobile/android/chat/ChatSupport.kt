package org.opencodemobile.android.chat

import org.opencodemobile.shared.domain.chat.ChatEvent
import org.opencodemobile.shared.domain.chat.ChatEventDecoder
import org.opencodemobile.shared.domain.chat.ChatNotConnectedException
import org.opencodemobile.shared.domain.chat.ChatPrompt
import org.opencodemobile.shared.domain.chat.OpenCodeChatGateway
import org.opencodemobile.shared.domain.chat.TranscriptMessage
import org.opencodemobile.shared.domain.event.EventSource

/**
 * The active server connection the V1-05 chat surface is scoped to.
 *
 * The app shell has no connection/onboarding composition root yet, so this seam
 * lets the chat graph be assembled today against an **optional** connection: the
 * connection root binds a `ChatConnection` once it exists, and the chat module
 * falls back to a fail-closed surface until then. It mirrors `SessionConnection`
 * / `PermissionConnection` and should be unified with them when the connection
 * root lands.
 *
 * `gateway` is the real `OpenCodeV2Adapter`; the seam never re-implements
 * networking, and the UI never gets an HTTP path.
 */
public interface ChatConnection {
    /** The real `OpenCodeV2Adapter` chat surface. */
    public val gateway: OpenCodeChatGateway

    /** The decoder for `message.*` realtime events. */
    public val decoder: ChatEventDecoder

    /** The realtime pipeline the transcript subscribes to. */
    public val source: EventSource

    /** The server profile the session is scoped to. */
    public val serverId: String

    /** The single project V1 opens, per OP3. */
    public val projectId: String
}

/**
 * Fail-closed [OpenCodeChatGateway] used while no connection is wired.
 *
 * It throws rather than returning an empty transcript: an empty transcript would
 * render "no transcript yet" and hide the real cause.
 */
internal object UnavailableChatGateway : OpenCodeChatGateway {
    override suspend fun transcript(sessionId: String): List<TranscriptMessage> =
        throw ChatNotConnectedException()

    override suspend fun sendPrompt(sessionId: String, prompt: ChatPrompt): Unit =
        throw ChatNotConnectedException()
}

/** A [ChatEventDecoder] that decodes nothing while no connection is wired. */
internal object UnavailableChatEventDecoder : ChatEventDecoder {
    override fun decode(type: String, payload: String): ChatEvent? = null
}

/**
 * A [OpenCodeChatGateway] that resolves the active [ChatConnection] **at call
 * time**, so a connection root that registers the seam after the Koin graph was
 * first forced still lets the transcript reach the server.
 */
internal class DeferredChatGateway(
    private val resolveConnection: () -> ChatConnection?,
) : OpenCodeChatGateway {
    private fun gateway(): OpenCodeChatGateway = resolveConnection()?.gateway ?: UnavailableChatGateway

    override suspend fun transcript(sessionId: String): List<TranscriptMessage> =
        gateway().transcript(sessionId)

    override suspend fun sendPrompt(sessionId: String, prompt: ChatPrompt): Unit =
        gateway().sendPrompt(sessionId, prompt)
}

/** A [ChatEventDecoder] that resolves the active decoder at call time. */
internal class DeferredChatEventDecoder(
    private val resolveConnection: () -> ChatConnection?,
) : ChatEventDecoder {
    override fun decode(type: String, payload: String): ChatEvent? =
        (resolveConnection()?.decoder ?: UnavailableChatEventDecoder).decode(type, payload)
}
