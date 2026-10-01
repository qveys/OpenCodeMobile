package org.opencodemobile.shared.domain.permission

/**
 * The app's decision vocabulary for a tool-call permission request (V1-06).
 *
 * The names are the product's, not the wire's: [Once] is "Allow once", [Deny] is
 * "Deny" and [Remember] is "Allow session". The exact server encoding (`once` /
 * `reject` / `always`) is owned by `shared/networking`, which is the only layer
 * allowed to know the HTTP contract. Keeping the two apart is what lets the UI
 * render "exactly the server-provided decisions" without the domain having to
 * speak the server's dialect.
 */
public enum class PermissionDecision {
    Once,
    Deny,
    Remember,
}

/**
 * The decisions a specific request actually exposes.
 *
 * This type exists so the app **cannot invent** an option the server does not
 * support: the UI renders [PermissionPolicy.availableDecisions], which is a pure
 * filter over this set. A server that only answers `once` maps to
 * [OnceOnly], and neither Deny nor Remember is presented.
 */
public data class PermissionCapabilities(
    public val decisions: Set<PermissionDecision>,
) {
    init {
        require(decisions.isNotEmpty()) {
            "a permission request must expose at least one decision"
        }
    }

    /** True when [decision] is one of the decisions the server exposed. */
    public fun allows(decision: PermissionDecision): Boolean = decision in decisions

    /** Stable, order-independent names for hashing and persistence. */
    public fun decisionNames(): List<String> = decisions.map { it.name }.sorted()

    public companion object {
        /**
         * A request whose server only answers `once`: the app must show a single
         * "Allow once" control and no Deny / Remember.
         */
        public val OnceOnly: PermissionCapabilities =
            PermissionCapabilities(setOf(PermissionDecision.Once))

        /**
         * Derives the decision set from a pinned OpenCode Server v2 root-surface
         * request.
         *
         * The root surface exposes the reply enum `once` / `reject` / `always`:
         * `once` is always available, `reject` (Deny) is always expressible, and
         * `always` (Remember) is only meaningful when the server offered at least
         * one persistable scope. [supportsDeny] lets a restricted server variant
         * advertise that it does not accept `reject`; the pinned default is true.
         *
         * Because the pinned spec has no per-request capability flag, this is the
         * best available derivation. The [PermissionCapabilities.OnceOnly] value
         * is what a request whose server reports [supportsDeny] = false maps to.
         */
        public fun fromServer(
            rememberScopes: List<String>,
            supportsDeny: Boolean = true,
        ): PermissionCapabilities {
            val decisions = mutableSetOf(PermissionDecision.Once)
            if (supportsDeny) decisions += PermissionDecision.Deny
            if (rememberScopes.isNotEmpty()) decisions += PermissionDecision.Remember
            return PermissionCapabilities(decisions)
        }
    }
}

/**
 * One pending tool-call permission request, as the server asked it.
 *
 * Faithfulness is a security property here (T2): every field the user is shown
 * comes straight from [tool], [patterns] and [rawArguments]. Nothing is
 * summarized, reformatted or elided, and [contentFingerprint] binds a confirmation
 * screen to the exact content it rendered.
 */
public data class PermissionRequest(
    /** Server-issued, single-use request id (`^per`). */
    public val id: String,
    /** Session that triggered the request, when the server reports one. */
    public val sessionId: String?,
    /** The requested tool, verbatim (for example `bash`). */
    public val tool: String,
    /** The exact targets the tool would touch, verbatim. */
    public val patterns: List<String>,
    /** The exact `metadata` object as the server sent it (JSON text), verbatim. */
    public val rawArguments: String,
    /** The persistable scopes the server offered; empty means no "Allow session". */
    public val rememberScopes: List<String>,
    /** The decisions the server exposed for this request. */
    public val capabilities: PermissionCapabilities,
) {
    init {
        require(id.isNotBlank()) { "permission request id must not be blank" }
        require(tool.isNotBlank()) { "permission request tool must not be blank" }
    }

    /**
     * A stable, platform-independent digest of exactly what the server asked,
     * **including the decision surface**.
     *
     * The confirmation screen arms itself with this value and the coordinator
     * refuses a submit whose request no longer matches: a request superseded or
     * changed between render and tap cannot be approved from a stale screen.
     * [capabilities] is part of the digest so that a change to the exposed
     * decisions (for example a restored request that gains `Remember`) also
     * invalidates a stale confirmation.
     */
    public val contentFingerprint: String
        get() = permissionContentFingerprint(
            id = id,
            sessionId = sessionId,
            tool = tool,
            patterns = patterns,
            rawArguments = rawArguments,
            rememberScopes = rememberScopes,
            decisions = capabilities.decisionNames(),
        )
}

/**
 * Display hardening for server-controlled text (T2).
 *
 * Compose does not interpret markup, so this is not injection protection. It
 * removes C0/C1 control characters and bidi/invisible formatting characters that
 * could visually reorder the one surface where the user must tell "Allow" from
 * "Deny". It is applied to the tool and target lines only; [PermissionRequest.rawArguments]
 * is shown byte-exact and is never passed through here.
 */
public object PermissionDisplay {
    private val BIDI_AND_INVISIBLE: Set<Char> = setOf(
        '\u202A', '\u202B', '\u202C', '\u202D', '\u202E',
        '\u2066', '\u2067', '\u2068', '\u2069',
        '\u200B', '\u200C', '\u200D', '\u200E', '\u200F',
        '\u061C', '\u00AD', '\uFEFF',
    )

    /** Returns [text] without control or bidi/invisible formatting characters. */
    public fun sanitizeForDisplay(text: String): String = buildString(text.length) {
        for (character in text) {
            if (character.isISOControl() || character in BIDI_AND_INVISIBLE) continue
            append(character)
        }
    }
}

/**
 * A read-only notification surface (OP4).
 *
 * There is deliberately **no approve member**: a notification can signal a
 * pending request and offer the safe, reversible "Deny" direction, but it can
 * never carry a control that authorizes execution. Approving is only possible on
 * the in-app foreground confirmation screen.
 */
public enum class PermissionNotificationAction {
    /** Brings the app forward to the confirmation screen; carries no decision. */
    OpenConfirmation,

    /** The safe direction: it can only make the agent ask again. */
    Deny,
}

/**
 * What a local notification may show for a pending permission request.
 *
 * [tapRoute] is the in-app route the notification opens. It names the
 * confirmation screen and the request id only; it never embeds a decision, so a
 * deep link cannot approve anything. [body] deliberately does **not** carry the
 * command or file paths: a notification is visible on a locked screen, and the
 * exact content must only be shown on the authenticated in-app surface (T13).
 */
public data class PermissionNotification(
    public val requestId: String,
    public val title: String,
    public val body: String,
    public val actions: List<PermissionNotificationAction>,
    public val tapRoute: String,
)

/**
 * Pure policy for the permission surface: faithful display, exact decision
 * relay, and the OP4 notification rule.
 */
public object PermissionPolicy {

    /**
     * Structural guarantee of OP4/T2, stated as a constant so the security
     * review has one place to point at: a notification never approves.
     */
    public const val NOTIFICATION_CAN_APPROVE: Boolean = false

    /** The route the notification opens: the confirmation screen, never an approval. */
    public const val CONFIRMATION_ROUTE_PREFIX: String = "opencodemobile://permission/confirmation/"

    /**
     * The decisions to render, in a stable order, filtered to exactly the set
     * the server exposed. The app never adds, renames or reorders what the
     * server offered.
     */
    public fun availableDecisions(request: PermissionRequest): List<PermissionDecision> =
        DECISION_ORDER.filter { request.capabilities.allows(it) }

    /**
     * Approving (once or session) requires the biometric / device-credential
     * gate; denying does not, because it grants no capability.
     */
    public fun requiresAuthentication(decision: PermissionDecision): Boolean =
        decision != PermissionDecision.Deny

    /**
     * The notification plan for [request]: informational only, plus the safe
     * "Deny" action when the server exposed it. Never an approval, and the tap
     * target is the foreground confirmation screen.
     *
     * The body names the tool only; it never includes the command or the target
     * paths, which are shown exclusively on the authenticated in-app screen.
     */
    public fun notificationFor(request: PermissionRequest): PermissionNotification {
        val actions = buildList {
            add(PermissionNotificationAction.OpenConfirmation)
            if (request.capabilities.allows(PermissionDecision.Deny)) {
                add(PermissionNotificationAction.Deny)
            }
        }
        return PermissionNotification(
            requestId = request.id,
            title = "Permission required · ${PermissionDisplay.sanitizeForDisplay(request.tool)}",
            body = "Open the app to review the exact request.",
            actions = actions,
            tapRoute = CONFIRMATION_ROUTE_PREFIX + request.id,
        )
    }

    private val DECISION_ORDER: List<PermissionDecision> = listOf(
        PermissionDecision.Once,
        PermissionDecision.Deny,
        PermissionDecision.Remember,
    )
}

/**
 * FNV-1a (64-bit), rendered as fixed-width lowercase hex.
 *
 * Deliberately hand-rolled instead of a platform digest so the value is
 * identical on Android and iOS and the domain stays free of platform APIs. It is
 * a change detector for the confirmation screen, not a cryptographic hash.
 *
 * The digest is computed over the **UTF-16 code units** of a length-prefixed
 * encoding; no byte masking is applied. Masking to the low byte would make any
 * two code points that share a low byte collide, which would let changed content
 * evade the confirmation screen's content binding.
 *
 * The explicit field parameters keep the digest contract grep-able at each call
 * site; `LongParameterList` is suppressed for this migrated helper (the
 * baseline-vs-refactor policy is tracked in OPE-221).
 */
@Suppress("LongParameterList")
public fun permissionContentFingerprint(
    id: String,
    sessionId: String?,
    tool: String,
    patterns: List<String>,
    rawArguments: String,
    rememberScopes: List<String>,
    decisions: List<String>,
): String {
    val payload = buildString {
        appendField(id)
        appendField(sessionId.orEmpty())
        appendField(tool)
        appendField(patterns.joinToString("\u0000"))
        appendField(rawArguments)
        appendField(rememberScopes.joinToString("\u0000"))
        appendField(decisions.joinToString("\u0000"))
    }
    var hash = -3750763034362895579L // 14695981039346656037 as a signed 64-bit value
    for (character in payload) {
        hash = hash xor character.code.toLong()
        hash *= 1099511628211L
    }
    return hash.toULong().toString(16).padStart(16, '0')
}

private fun StringBuilder.appendField(value: String) {
    append(value)
    append('\u0001')
}
