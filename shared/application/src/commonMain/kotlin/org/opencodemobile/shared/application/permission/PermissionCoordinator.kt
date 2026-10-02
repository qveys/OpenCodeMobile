package org.opencodemobile.shared.application.permission

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.opencodemobile.shared.domain.cache.MutationGate
import org.opencodemobile.shared.domain.permission.BiometricAuthenticator
import org.opencodemobile.shared.domain.permission.BiometricResult
import org.opencodemobile.shared.domain.permission.FailClosedBiometricAuthenticator
import org.opencodemobile.shared.domain.permission.NoOpPermissionNotifier
import org.opencodemobile.shared.domain.permission.PermissionDecision
import org.opencodemobile.shared.domain.permission.PermissionEvent
import org.opencodemobile.shared.domain.permission.PermissionNotifier
import org.opencodemobile.shared.domain.permission.PermissionPolicy
import org.opencodemobile.shared.domain.permission.PermissionPort
import org.opencodemobile.shared.domain.permission.PermissionReplyOutcome
import org.opencodemobile.shared.domain.permission.PermissionRequest
import org.opencodemobile.shared.domain.permission.PendingPermissionStore

/**
 * The observable permission surface (V1-06): what the non-dismissable banner and
 * the confirmation screen render.
 */
public data class PermissionState(
    /** The pending requests, in arrival order. Non-empty means the banner is up. */
    public val pending: List<PermissionRequest> = emptyList(),
    /** True while the app is the foreground, focused activity/scene (T2). */
    public val foregrounded: Boolean = false,
    /** The request the confirmation screen is currently armed for, if any. */
    public val armedRequestId: String? = null,
    /** Fingerprint of the content the armed screen rendered (T2, T6). */
    public val armedFingerprint: String? = null,
) {
    /** The banner is shown exactly while a permission is pending. */
    public val bannerVisible: Boolean get() = pending.isNotEmpty()

    /** The request the banner surfaces first. */
    public val activeRequest: PermissionRequest? get() = pending.firstOrNull()

    /** The pending request with [id], or null when it is not (or no longer) pending. */
    public fun request(id: String): PermissionRequest? = pending.firstOrNull { it.id == id }
}

/** Outcome of a decision submit. */
public sealed interface PermissionSubmitResult {
    /** The server accepted the decision; the request is no longer pending. */
    public data object Accepted : PermissionSubmitResult

    /** The server refused the decision; [reason] is safe to show. */
    public data class Rejected(public val reason: String) : PermissionSubmitResult

    /** The app is offline: mutating actions are disabled, nothing is queued (D8/T12). */
    public data object Offline : PermissionSubmitResult

    /** The request is not pending (already decided, superseded, or unknown). */
    public data object NotFound : PermissionSubmitResult

    /** The server did not expose [decision] for this request ("no invented scope"). */
    public data class Unavailable(public val decision: PermissionDecision) : PermissionSubmitResult

    /** The app is not in the foreground; the pending confirmation is discarded. */
    public data object NotForegrounded : PermissionSubmitResult

    /** The biometric / device-credential gate did not succeed. [reason] is safe to show. */
    public data class NotAuthenticated(public val reason: String) : PermissionSubmitResult

    /** The confirmation screen was not armed (for example it was backgrounded). */
    public data object NotArmed : PermissionSubmitResult

    /** The request changed since the screen rendered it; the user must review again. */
    public data object ContentChanged : PermissionSubmitResult

    /** Transport failure. Nothing is retried or replayed (D9). */
    public data class Failed(public val reason: String) : PermissionSubmitResult
}

/**
 * Owns the pending-permission state machine (V1-06).
 *
 * Responsibilities:
 * - ingest `permission.asked` / `permission.replied` events,
 * - restore persisted pending requests across an app kill, then reconcile with
 *   the server (the server is authoritative),
 * - refuse to approve from anywhere but a foreground confirmed screen, after the
 *   platform biometric gate, bound to the exact content that was rendered,
 * - relay exactly one decision for exactly one request (D9),
 * - signal each pending change to [PermissionNotifier] (informational only, OP4).
 *
 * It never approves on its own: [start], [reconcile] and [onEvent] only move the
 * pending set; the only paths to [PermissionPort.reply] are [approve] and [deny].
 */
@Suppress("TooManyFunctions") // one cohesive state machine; splitting would scatter the T2 invariants
public class PermissionCoordinator(
    private val port: PermissionPort,
    private val store: PendingPermissionStore,
    private val mutationGate: MutationGate,
    private val biometricAuthenticator: BiometricAuthenticator = FailClosedBiometricAuthenticator,
    private val notifier: PermissionNotifier = NoOpPermissionNotifier,
) {

    private val mutableState = MutableStateFlow(PermissionState())
    public val state: StateFlow<PermissionState> = mutableState.asStateFlow()

    private val lock = Mutex()

    /**
     * Ids already answered (locally or reported by the server). A replayed or
     * out-of-order `permission.asked` for one of them is dropped instead of
     * resurrecting a decided request (T6). The window is bounded; reconciliation
     * is the backstop.
     */
    private val decidedIds = LinkedHashSet<String>()

    /**
     * Restores the persisted pending set, then (when online) reconciles it with
     * the server. Restoring a request is not approving it: no decision is ever
     * sent from here.
     */
    public suspend fun start() {
        val stored = runCatching { store.load() }.getOrDefault(emptyList())
        replacePending(stored)
        if (mutationGate.mutationsAllowed()) {
            reconcile()
        }
    }

    /**
     * Replaces the pending set with the server's authoritative list.
     *
     * A request decided elsewhere while the app was killed disappears here and is
     * never re-surfaced. A transport failure leaves the current set untouched.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // fail closed: any port failure must leave the set untouched, never crash
    public suspend fun reconcile() {
        val serverPending = try {
            port.pendingPermissions()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            return
        }
        // Server-issued ids that are no longer pending are recorded as decided so
        // an out-of-order `permission.asked` cannot resurrect them.
        val serverIds = serverPending.map { it.id }.toSet()

        // A successful GET is authoritative, but a response captured during an SSE
        // reconnect can be momentarily empty or truncated. Absence is therefore not
        // proof a request was decided (N1): one omission must not erase the banner
        // *and* permanently suppress that request's event. So an id the server
        // reports pending again drops any tombstone recorded by an earlier,
        // incomplete reconcile. The set stays bounded by `rememberDecided`.
        for (id in serverIds) {
            decidedIds.remove(id)
        }
        for (existing in mutableState.value.pending) {
            if (existing.id !in serverIds) rememberDecided(existing.id)
        }
        replacePending(serverPending)
    }

    /**
     * Applies one decoded realtime event to the pending set.
     *
     * [PermissionEvent.Replied] for an unknown id is still recorded as decided:
     * a reordered pair (`replied` before `asked`) must not let the already-decided
     * request be re-surfaced as pending (T6).
     */
    public suspend fun onEvent(event: PermissionEvent) {
        when (event) {
            is PermissionEvent.Asked -> {
                val request = event.request
                if (request.id in decidedIds) return
                val current = mutableState.value.pending
                if (current.any { it.id == request.id }) return
                replacePending(current + request)
            }

            is PermissionEvent.Replied -> {
                rememberDecided(event.requestId)
                if (mutableState.value.request(event.requestId) == null) return
                replacePending(mutableState.value.pending.filterNot { it.id == event.requestId })
            }
        }
    }

    /** Marks the current request as the foreground confirmation target. */
    public fun arm(requestId: String) {
        val current = mutableState.value
        if (!current.foregrounded) return
        val request = current.request(requestId) ?: return
        mutableState.value = current.copy(
            armedRequestId = requestId,
            armedFingerprint = request.contentFingerprint,
        )
    }

    /** Drops the armed confirmation state. */
    public fun disarm() {
        mutableState.value = mutableState.value.copy(
            armedRequestId = null,
            armedFingerprint = null,
        )
    }

    /**
     * Tracks the app's foreground state. Leaving the foreground discards the
     * armed confirmation instead of queueing it, so a tap that lands after a
     * background/foreground transition cannot submit (T2).
     */
    public fun onForegroundChanged(foregrounded: Boolean) {
        mutableState.value = if (foregrounded) {
            mutableState.value.copy(foregrounded = true)
        } else {
            mutableState.value.copy(
                foregrounded = false,
                armedRequestId = null,
                armedFingerprint = null,
            )
        }
    }

    /**
     * Convenience dispatcher: [PermissionDecision.Deny] takes the safe [deny]
     * path, every other decision takes the authenticated [approve] path.
     */
    public suspend fun submit(
        requestId: String,
        decision: PermissionDecision,
        displayedFingerprint: String,
    ): PermissionSubmitResult = if (decision == PermissionDecision.Deny) {
        deny(requestId)
    } else {
        approve(requestId, decision, displayedFingerprint)
    }

    /**
     * Denies [requestId]. Denying grants no capability, so it needs neither the
     * foreground confirmation nor the biometric gate (T2): it is the safe,
     * reversible direction, and is the only decision a notification may offer.
     */
    public suspend fun deny(requestId: String): PermissionSubmitResult {
        if (!mutationGate.mutationsAllowed()) return PermissionSubmitResult.Offline
        val request = mutableState.value.request(requestId) ?: return PermissionSubmitResult.NotFound
        if (!request.capabilities.allows(PermissionDecision.Deny)) {
            return PermissionSubmitResult.Unavailable(PermissionDecision.Deny)
        }
        return send(requestId, PermissionDecision.Deny)
    }

    /**
     * Approves [requestId] with an approving [decision], enforcing every T2 gate:
     * online, server-exposed decision, coordinator foreground state, an armed
     * confirmation bound to the current content, and the platform biometric /
     * device-credential gate immediately before the send.
     *
     * [displayedFingerprint] is the fingerprint of the content the confirmation
     * screen rendered (the banner model the screen holds); it must still match the
     * live request, and the foreground, armed-confirmation and content binding are
     * all re-checked again after the biometric prompt, because the activity can be
     * stopped and the content can change while the user authenticates.
     */
    public suspend fun approve(
        requestId: String,
        decision: PermissionDecision,
        displayedFingerprint: String,
    ): PermissionSubmitResult {
        if (decision == PermissionDecision.Deny) {
            return PermissionSubmitResult.Unavailable(PermissionDecision.Deny)
        }
        val request = mutableState.value.request(requestId) ?: return PermissionSubmitResult.NotFound
        preflightRefusal(request, decision, displayedFingerprint)?.let { return it }

        // Biometric / device-credential gate, once per approval, immediately
        // before the decision is sent.
        val biometric = biometricAuthenticator.authenticate(
            reason = "Approve permission for ${request.tool}",
        )
        if (biometric !is BiometricResult.Succeeded) {
            return PermissionSubmitResult.NotAuthenticated(biometric.describe())
        }

        // The content can change while the user authenticates, and the activity can
        // be stopped while the prompt is up (which disarms and clears the
        // foreground flag). Re-read the live state and re-assert the whole T2
        // invariant at the enforcement point, not just the content fingerprint.
        postBiometricRefusal(request)?.let { return it }

        return send(requestId, decision).also {
            if (it == PermissionSubmitResult.Accepted) disarm()
        }
    }

    /** Every T2 gate that can be checked before the biometric prompt; null when all pass. */
    private fun preflightRefusal(
        request: PermissionRequest,
        decision: PermissionDecision,
        displayedFingerprint: String,
    ): PermissionSubmitResult? {
        val current = mutableState.value
        return when {
            !mutationGate.mutationsAllowed() -> PermissionSubmitResult.Offline
            !request.capabilities.allows(decision) -> PermissionSubmitResult.Unavailable(decision)
            !current.foregrounded -> PermissionSubmitResult.NotForegrounded
            current.armedRequestId != request.id -> PermissionSubmitResult.NotArmed
            current.armedFingerprint != request.contentFingerprint ||
                displayedFingerprint != request.contentFingerprint -> PermissionSubmitResult.ContentChanged
            else -> null
        }
    }

    /** Re-asserts the T2 invariant on the live state after the biometric prompt; null when it holds. */
    private fun postBiometricRefusal(request: PermissionRequest): PermissionSubmitResult? {
        val live = mutableState.value
        val liveRequest = live.request(request.id)
        return when {
            !live.foregrounded -> PermissionSubmitResult.NotForegrounded
            live.armedRequestId != request.id -> PermissionSubmitResult.NotArmed
            liveRequest == null -> PermissionSubmitResult.NotFound
            liveRequest.contentFingerprint != request.contentFingerprint -> PermissionSubmitResult.ContentChanged
            else -> null
        }
    }

    @Suppress("TooGenericExceptionCaught") // any port failure maps to Failed; cancellation is rethrown above
    private suspend fun send(
        requestId: String,
        decision: PermissionDecision,
    ): PermissionSubmitResult {
        val outcome = try {
            port.reply(requestId, decision)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            return PermissionSubmitResult.Failed(failure.message ?: "permission reply failed")
        }

        return when (outcome) {
            PermissionReplyOutcome.Accepted -> {
                rememberDecided(requestId)
                // Remove from the *live* set, not a pre-call snapshot: a
                // permission.asked that arrived during the round trip must not be
                // dropped from the banner.
                replacePending(mutableState.value.pending.filterNot { it.id == requestId })
                PermissionSubmitResult.Accepted
            }

            is PermissionReplyOutcome.Rejected -> PermissionSubmitResult.Rejected(outcome.reason)
        }
    }

    private fun rememberDecided(requestId: String) {
        decidedIds.add(requestId)
        while (decidedIds.size > MAX_REMEMBERED_DECIDED) {
            val eldest = decidedIds.iterator()
            if (eldest.hasNext()) {
                eldest.next()
                eldest.remove()
            }
        }
    }

    private suspend fun replacePending(pending: List<PermissionRequest>) = lock.withLock {
        mutableState.value = mutableState.value.copy(pending = pending)
        runCatching { store.save(pending) }
        runCatching { notifier.onPendingChanged(pending) }
    }

    private fun BiometricResult.describe(): String = when (this) {
        BiometricResult.Succeeded -> "authenticated"
        BiometricResult.Cancelled -> "authentication cancelled"
        is BiometricResult.Failed -> reason
        BiometricResult.Unavailable -> "no biometric or device credential is available"
    }

    private companion object {
        private const val MAX_REMEMBERED_DECIDED: Int = 256
    }
}
