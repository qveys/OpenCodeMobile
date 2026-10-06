package org.opencodemobile.shared.application.interaction

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.opencodemobile.shared.domain.cache.CacheMutationNotAllowedException
import org.opencodemobile.shared.domain.cache.MutationGate
import org.opencodemobile.shared.domain.interaction.OpenCodeInteractionGateway

/**
 * State of the abort surface (V1-08).
 *
 * After a successful abort the client holds **no** local turn state: it waits
 * for the next authoritative server snapshot before trusting event-derived
 * state for that session again. That is the "the next snapshot wins" rule of
 * `docs/ARCHITECTURE.md` §3.2, and it is what stops a late event from the
 * aborted turn from being rendered as an orphan.
 */
public data class TurnAbortState(
    /** Session whose abort request is in flight, if any. */
    public val abortingSessionId: String? = null,
    /**
     * Session whose event-derived state must be ignored until a fresh server
     * snapshot lands.
     */
    public val awaitingAuthoritativeSnapshotFor: String? = null,
    public val error: String? = null,
) {
    public fun isAborting(sessionId: String): Boolean = abortingSessionId == sessionId

    public fun isAwaitingResync(sessionId: String): Boolean =
        awaitingAuthoritativeSnapshotFor == sessionId
}

/**
 * Aborts the active turn of a session (V1-08).
 *
 * - **Explicit only.** Nothing here is automatic; [abort] runs only when the
 *   caller asks for it.
 * - **Exactly once.** There is no retry loop: a failed abort fails and the user
 *   decides whether to try again. Re-issuing it would be a second user action.
 * - **Offline read-only.** [MutationGate] blocks the action offline and nothing
 *   is queued (D8).
 */
public class TurnAbortController(
    private val gateway: OpenCodeInteractionGateway,
    private val mutationGate: MutationGate,
) {
    private val _state = MutableStateFlow(TurnAbortState())

    public val state: StateFlow<TurnAbortState> = _state.asStateFlow()

    /**
     * Aborts [sessionId]; on success the session waits for a new snapshot.
     *
     * [directory] is the active project root; it is forwarded to the server so
     * the abort is scoped to this workspace (ADR-0002 §3.3).
     */
    public suspend fun abort(sessionId: String, directory: String? = null): Result<Unit> {
        if (!mutationGate.mutationsAllowed()) {
            return Result.failure(CacheMutationNotAllowedException())
        }
        _state.value = TurnAbortState(abortingSessionId = sessionId)
        return runCatchingNonCancellable { gateway.abortTurn(sessionId, directory) }.fold(
            onSuccess = {
                _state.value = TurnAbortState(awaitingAuthoritativeSnapshotFor = sessionId)
                Result.success(Unit)
            },
            onFailure = { failure ->
                _state.value = TurnAbortState(error = failure.messageOrType())
                Result.failure(failure)
            },
        )
    }

    /**
     * Records that the realtime pipeline published a new authoritative snapshot.
     * Pass [sessionId] to clear one session, or null to clear all.
     */
    public fun onAuthoritativeSnapshot(sessionId: String? = null) {
        val awaiting = _state.value.awaitingAuthoritativeSnapshotFor
        if (sessionId == null || sessionId == awaiting) {
            _state.value = _state.value.copy(awaitingAuthoritativeSnapshotFor = null)
        }
    }

    /**
     * Whether event-derived state for [sessionId] may be shown. False from a
     * successful abort until [onAuthoritativeSnapshot] is called.
     */
    public fun acceptsEventDerivedState(sessionId: String): Boolean =
        !_state.value.isAwaitingResync(sessionId)
}
