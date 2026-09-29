package org.opencodemobile.shared.application.permission

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.opencodemobile.shared.domain.cache.MutationGate
import org.opencodemobile.shared.domain.permission.PermissionDecision
import org.opencodemobile.shared.domain.permission.PermissionEvent
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

/** The evidence the confirmation screen presents when the user submits. */
public data class PermissionConfirmation(
    /** The app was the foreground, focused surface at the moment of the tap. */
    public val foregrounded: Boolean,
    /** The biometric / device-credential gate passed immediately before the tap. */
    public val authenticated: Boolean,
)

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

    /** The approval requires the authentication gate and it did not pass. */
    public data object NotAuthenticated : PermissionSubmitResult

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
 * - refuse to approve from anywhere but a foreground, authenticated confirmation
 *   screen, bound to the exact content that was rendered,
 * - relay exactly one decision for exactly one request (D9).
 *
 * It never approves on its own: [start], [reconcile] and [onEvent] only move the
 * pending set; the only path to [PermissionPort.reply] is [submit].
 */
public class PermissionCoordinator(
    private val port: PermissionPort,
    private val store: PendingPermissionStore,
    private val mutationGate: MutationGate,
) {

    private val mutableState = MutableStateFlow(PermissionState())
    public val state: StateFlow<PermissionState> = mutableState.asStateFlow()

    private val lock = Mutex()

    /**
     * Ids already answered in this process. A replayed or out-of-order
     * `permission.asked` for one of them is dropped instead of resurrecting a
     * decided request (T6). The window is bounded; reconciliation is the backstop.
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
    public suspend fun reconcile() {
        val serverPending = try {
            port.pendingPermissions()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            return
        }
        replacePending(serverPending)
    }

    /**
     * Applies one decoded realtime event to the pending set.
     *
     * When [PermissionEvent.Asked] is a replay of a request already answered in
     * this process it is ignored; a duplicate of a still-pending request is
     * collapsed onto the existing entry.
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
     * Submits one decision, enforcing every T2 gate in order. At most one
     * [PermissionPort.reply] call happens per accepted submit; nothing is retried.
     */
    public suspend fun submit(
        requestId: String,
        decision: PermissionDecision,
        confirmation: PermissionConfirmation,
    ): PermissionSubmitResult {
        val current = mutableState.value
        val request = current.request(requestId) ?: return PermissionSubmitResult.NotFound
        if (!mutationGate.mutationsAllowed()) return PermissionSubmitResult.Offline
        if (!request.capabilities.allows(decision)) {
            return PermissionSubmitResult.Unavailable(decision)
        }
        if (!confirmation.foregrounded) return PermissionSubmitResult.NotForegrounded
        if (PermissionPolicy.requiresAuthentication(decision) && !confirmation.authenticated) {
            return PermissionSubmitResult.NotAuthenticated
        }
        if (current.armedRequestId != requestId) return PermissionSubmitResult.NotArmed
        if (current.armedFingerprint != request.contentFingerprint) {
            return PermissionSubmitResult.ContentChanged
        }

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
                replacePending(current.pending.filterNot { it.id == requestId })
                disarm()
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
    }

    private companion object {
        private const val MAX_REMEMBERED_DECIDED: Int = 256
    }
}
