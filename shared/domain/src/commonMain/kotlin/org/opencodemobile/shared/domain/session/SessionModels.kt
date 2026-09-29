package org.opencodemobile.shared.domain.session

/**
 * A session as the V1-04 list renders it (`docs/ARCHITECTURE.md` §3.4).
 *
 * It is a small projection of the server's session object: only the fields the
 * list and the resume/open action need. The domain never sees the generated
 * `ApiSession` type (Rule R3); the adapter maps one to the other.
 *
 * [title] is always a displayable, non-blank string: a session the server has
 * not titled yet falls back to its id, so the list never renders an empty row.
 */
public data class SessionSummary(
    public val id: String,
    public val title: String,
    public val directory: String? = null,
    public val projectId: String? = null,
    public val parentSessionId: String? = null,
    public val createdAt: Long = 0L,
    public val updatedAt: Long = 0L,
)

/**
 * The session surface the *connected* server actually exposes.
 *
 * It is derived at runtime from the server's published API document (the OpenAPI
 * document at `GET /doc`), never from a hard-coded catalog: a server that does
 * not publish the fork route reports [forkAvailable] as `false`, and the UI
 * disables the action instead of sending a call the server would reject
 * (V1-04 acceptance).
 */
public data class SessionCapabilities(
    /** True only when the server's published surface includes `POST /session/{id}/fork`. */
    public val forkAvailable: Boolean,
) {
    public companion object {
        /**
         * The fail-closed default. When the server surface cannot be read, the
         * app does not assume a capability it has not verified: fork stays
         * hidden rather than rejected at the wire.
         */
        public val Unknown: SessionCapabilities = SessionCapabilities(forkAvailable = false)
    }
}

/**
 * Title rules shared by the list UI and the gateway, so the exact same
 * normalization is applied before a title is validated or sent.
 *
 * A title is normalized by trimming it and collapsing internal whitespace, then
 * bounded to [MAX_LENGTH] characters. A blank normalized title is invalid: the
 * app never sends an empty rename.
 */
public object SessionTitlePolicy {
    /** Longest title the app accepts. Long enough for a sentence, short enough for a row. */
    public const val MAX_LENGTH: Int = 120

    /** Trims and collapses whitespace; null when nothing displayable remains. */
    public fun normalize(raw: String): String? =
        raw.trim()
            .replace(WHITESPACE_RUN, " ")
            .takeIf { it.isNotEmpty() }

    /** Whether [raw] normalizes to a non-blank title within [MAX_LENGTH]. */
    public fun isValid(raw: String): Boolean {
        val normalized = normalize(raw) ?: return false
        return normalized.length <= MAX_LENGTH
    }

    private val WHITESPACE_RUN = Regex("\\s+")
}
