package org.opencodemobile.features.settings

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.opencodemobile.shared.application.erasure.EraseEverythingCoordinator
import org.opencodemobile.shared.application.erasure.EraseEverythingReport
import org.opencodemobile.shared.domain.localaccess.LocalAccessSettingsStore
import org.opencodemobile.shared.domain.permission.BiometricAuthenticator
import org.opencodemobile.shared.domain.permission.BiometricResult
import org.opencodemobile.shared.domain.permission.FailClosedBiometricAuthenticator

/**
 * The observable states of the "Tout effacer" surface.
 *
 * The action cannot run without passing through [Confirming]: there is no code
 * path from [Idle] straight to [Erasing], which is what makes "never silent,
 * never automatic" (ADR 0009 §2.2) a structural property, not a convention.
 */
public sealed interface EraseEverythingUiState {
    /** Nothing in progress; the destructive action is offered. */
    public data object Idle : EraseEverythingUiState

    /** A confirmation dialog is open, naming what will be erased. */
    public data object Confirming : EraseEverythingUiState

    /** The confirmed erase is running. */
    public data object Erasing : EraseEverythingUiState

    /** The erase finished and every store was emptied. */
    public data class Erased(public val report: EraseEverythingReport) : EraseEverythingUiState

    /** The erase left a residue or could not complete. */
    public data class Failed(public val report: EraseEverythingReport? = null) : EraseEverythingUiState

    /** The optional biometric gate refused or was cancelled; nothing was erased. */
    public data object ReauthenticationFailed : EraseEverythingUiState
}

/** The user intents the "Tout effacer" screen forwards to its presenter. */
public data class EraseEverythingActions(
    public val requestErase: () -> Unit,
    public val confirmErase: () -> Unit,
    public val cancelErase: () -> Unit,
    public val dismiss: () -> Unit,
)

/**
 * Presenter for "Tout effacer" (ADR 0009).
 *
 * It owns the confirmation state machine and the optional biometric gate, and
 * delegates the actual wipe to [EraseEverythingCoordinator] in
 * `shared/application`. It is platform-free so Android and iOS render the exact
 * same behaviour and a common unit test covers the "no erase without
 * confirmation" rule.
 *
 * The biometric gate is only consulted when the user enabled it
 * ([LocalAccessSettingsStore]); when it is unavailable (not enrolled) the erase
 * proceeds, because ADR 0009 §2.3 makes biometrics an optional addition, never a
 * hard requirement.
 */
public class EraseEverythingController(
    private val coordinator: EraseEverythingCoordinator,
    private val settingsStore: LocalAccessSettingsStore,
    private val biometric: BiometricAuthenticator = FailClosedBiometricAuthenticator,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow<EraseEverythingUiState>(EraseEverythingUiState.Idle)

    /** Observable UI state. */
    public val state: StateFlow<EraseEverythingUiState> = mutableState.asStateFlow()

    /** Asks for confirmation; the confirmation UI lists these categories. */
    public fun requestErase() {
        mutableState.value = EraseEverythingUiState.Confirming
    }

    /** Dismisses the confirmation without erasing anything. */
    public fun cancelErase() {
        mutableState.value = EraseEverythingUiState.Idle
    }

    /**
     * Runs the erase after the user explicitly confirmed it.
     *
     * It is a no-op unless the state is [EraseEverythingUiState.Confirming], so
     * a stray caller cannot trigger the wipe.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // the failure is mapped to the Failed state, not the caller
    public fun confirmErase() {
        if (mutableState.value !is EraseEverythingUiState.Confirming) return
        mutableState.value = EraseEverythingUiState.Erasing
        scope.launch {
            if (!reauthenticated()) {
                mutableState.value = EraseEverythingUiState.ReauthenticationFailed
                return@launch
            }
            val report = try {
                coordinator.erase()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                mutableState.value = EraseEverythingUiState.Failed(report = null)
                return@launch
            }
            mutableState.value = if (report.complete) {
                EraseEverythingUiState.Erased(report)
            } else {
                EraseEverythingUiState.Failed(report)
            }
        }
    }

    /** Returns to [EraseEverythingUiState.Idle] after a result was shown. */
    public fun dismiss() {
        mutableState.value = EraseEverythingUiState.Idle
    }

    private suspend fun reauthenticated(): Boolean {
        if (!settingsStore.load().optionalBiometricsEnabled) return true
        return when (biometric.authenticate(REASON)) {
            BiometricResult.Succeeded -> true
            // Not enrolled / no authenticator: optional biometrics never block
            // an erase (ADR 0009 §2.3).
            BiometricResult.Unavailable -> true
            BiometricResult.Cancelled, is BiometricResult.Failed -> false
        }
    }

    public companion object {
        /** The short reason shown in the biometric prompt. */
        public const val REASON: String = "Confirm erasing local data"
    }
}
