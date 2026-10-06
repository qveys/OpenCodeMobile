package org.opencodemobile.features.permissions

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.opencodemobile.shared.application.permission.PermissionCoordinator
import org.opencodemobile.shared.application.permission.PermissionState
import org.opencodemobile.shared.application.permission.PermissionSubmitResult
import org.opencodemobile.shared.domain.permission.PermissionDecision
import org.opencodemobile.shared.domain.permission.PermissionDisplay
import org.opencodemobile.shared.domain.permission.PermissionEvent
import org.opencodemobile.shared.domain.permission.PermissionPolicy
import org.opencodemobile.shared.domain.permission.PermissionRequest

/**
 * The visual weight of a decision control, per `docs/DESIGN-SYSTEM.md`
 * §"PermissionBanner": Deny is `danger`, "Allow once" is the primary action and
 * "Allow session" is the secondary (`ghost`) one.
 */
public enum class PermissionEmphasis {
    Primary,
    Ghost,
    Danger,
}

/** One decision control, exactly as the server exposed it. */
public data class PermissionDecisionUi(
    public val decision: PermissionDecision,
    public val label: String,
    public val emphasis: PermissionEmphasis,
    /** True when submitting this decision must pass the biometric gate. */
    public val requiresAuthentication: Boolean,
)

/**
 * What the sticky permission banner renders. Every value comes straight from the
 * server request; nothing is summarized or reworded (V1-06 "affichage fidele").
 *
 * [tool] and [targets] are stripped of control/bidi characters so a hostile
 * server cannot visually reorder the decision controls; [argumentsText] is the
 * exact server JSON and is shown byte-for-byte.
 */
public data class PermissionBannerModel(
    public val requestId: String,
    public val tool: String,
    public val targets: List<String>,
    /** The exact server arguments, shown verbatim (never truncated). */
    public val argumentsText: String,
    public val decisions: List<PermissionDecisionUi>,
    /**
     * Fingerprint of the exact content this banner model rendered. The screen
     * passes it back when it submits, so the coordinator compares the content the
     * user actually saw against the live request (N3: not the same live value).
     */
    public val contentFingerprint: String,
)

/** The permission surface, derived from the application state. */
public data class PermissionUiState(
    public val banner: PermissionBannerModel? = null,
    /** True while the foreground confirmation screen is armed for [banner]. */
    public val confirmationOpen: Boolean = false,
) {
    public companion object {
        public fun from(state: PermissionState): PermissionUiState {
            val active = state.activeRequest
            return PermissionUiState(
                banner = active?.toBannerModel(),
                confirmationOpen = state.armedRequestId != null &&
                    state.armedRequestId == active?.id,
            )
        }
    }
}

private fun PermissionRequest.toBannerModel(): PermissionBannerModel = PermissionBannerModel(
    requestId = id,
    tool = PermissionDisplay.sanitizeForDisplay(tool),
    targets = patterns.map { PermissionDisplay.sanitizeForDisplay(it) },
    argumentsText = rawArguments,
    decisions = PermissionPolicy.availableDecisions(this).map { it.toDecisionUi() },
    contentFingerprint = contentFingerprint,
)

private fun PermissionDecision.toDecisionUi(): PermissionDecisionUi = PermissionDecisionUi(
    decision = this,
    label = when (this) {
        PermissionDecision.Once -> "Allow once"
        PermissionDecision.Deny -> "Deny"
        PermissionDecision.Remember -> "Allow session"
    },
    emphasis = when (this) {
        PermissionDecision.Once -> PermissionEmphasis.Primary
        PermissionDecision.Deny -> PermissionEmphasis.Danger
        PermissionDecision.Remember -> PermissionEmphasis.Ghost
    },
    requiresAuthentication = PermissionPolicy.requiresAuthentication(this),
)

/**
 * State holder the Compose layer observes.
 *
 * It is intentionally thin: every gate (offline, foreground, biometric,
 * content binding) lives in [PermissionCoordinator]; the presenter only forwards
 * user intents and maps state to the view model.
 */
public class PermissionsPresenter(
    private val coordinator: PermissionCoordinator,
    scope: CoroutineScope,
) {
    public val state: StateFlow<PermissionUiState> =
        coordinator.state
            .map { PermissionUiState.from(it) }
            .stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = PermissionUiState.from(coordinator.state.value),
            )

    public fun onForegroundChanged(foregrounded: Boolean): Unit =
        coordinator.onForegroundChanged(foregrounded)

    /** Opens the confirmation screen for [requestId] (arms the content binding). */
    public fun openConfirmation(requestId: String): Unit = coordinator.arm(requestId)

    /** Returns to the still-pending banner without deciding anything. */
    public fun closeConfirmation(): Unit = coordinator.disarm()

    public suspend fun onEvent(event: PermissionEvent): Unit = coordinator.onEvent(event)

    public suspend fun reconcile(): Unit = coordinator.reconcile()

    /**
     * Denies [requestId] directly (safe, reversible): this is the only decision
     * the banner and a notification may submit without the confirmation screen.
     */
    public suspend fun deny(requestId: String): PermissionSubmitResult =
        coordinator.deny(requestId)

    /**
     * Submits an approving decision taken on the confirmation screen. The
     * coordinator runs the biometric gate, checks the foreground, and re-checks
     * the content binding against what the screen rendered.
     *
     * [displayedFingerprint] must be the fingerprint carried by the banner model
     * the screen rendered ([PermissionBannerModel.contentFingerprint]), not one
     * read back from the coordinator: the coordinator compares the two, so a screen
     * showing stale content is refused.
     */
    public suspend fun approve(
        requestId: String,
        decision: PermissionDecision,
        displayedFingerprint: String,
    ): PermissionSubmitResult =
        coordinator.approve(requestId, decision, displayedFingerprint)
}
