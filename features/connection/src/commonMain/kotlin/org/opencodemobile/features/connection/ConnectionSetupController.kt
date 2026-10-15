package org.opencodemobile.features.connection

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.opencodemobile.shared.application.connection.ConnectionValidation
import org.opencodemobile.shared.application.connection.ServerConnectionSetup
import org.opencodemobile.shared.application.connection.ServerSetupPlan
import org.opencodemobile.shared.application.connection.ServerSetupPlanning
import org.opencodemobile.shared.application.connection.ServerSetupSource
import org.opencodemobile.shared.domain.connection.BasicAuthCredential
import org.opencodemobile.shared.domain.connection.ConnectionHandshake
import org.opencodemobile.shared.domain.connection.DomainError
import org.opencodemobile.shared.domain.connection.ServerCredential
import org.opencodemobile.shared.domain.connection.ServerFingerprint
import org.opencodemobile.shared.domain.connection.ServerProfile
import org.opencodemobile.shared.domain.connection.toDomainError

/**
 * State holder behind the manual-entry and import-review surfaces.
 *
 * It contains no storage or transport of its own: the composition root injects
 * the [ServerConnectionSetup] use case, a credential provider (which reads the
 * platform secure store) and an identity confirmer (which persists the accepted
 * TOFU pin). Keeping those behind function ports lets the controller be tested
 * with fakes and keeps secrets out of the feature UI.
 *
 * Provider failures (e.g. a `DomainError.StorageFailure` from the secure store)
 * are mapped to the failure state rather than escaping the coroutine, so the UI
 * always has a single, typed decision surface.
 */
@Suppress("LongParameterList", "TooManyFunctions", "TooGenericExceptionCaught")
public class ConnectionSetupController(
    private val setup: ServerConnectionSetup,
    private val scope: CoroutineScope,
    private val credentialProvider: suspend (ServerProfile) -> ServerCredential? = { null },
    private val existingProfileProvider: suspend () -> ServerProfile? = { null },
    private val existingFingerprintProvider: suspend (ServerProfile) -> ServerFingerprint? = { null },
    private val identityConfirmer: suspend (ServerProfile, ServerFingerprint) -> Unit = { _, _ -> },
    /** Reads a previously-saved reverse-proxy Basic Auth credential, so it round-trips back into the UI. */
    private val basicAuthProvider: suspend (ServerProfile) -> BasicAuthCredential? = { null },
    /** Persists the reverse-proxy Basic Auth credential the user typed, keyed by the connecting profile. */
    private val basicAuthPersister: suspend (ServerProfile, BasicAuthCredential) -> Unit = { _, _ -> },
    /**
     * Called once the handshake succeeded, with the profile that was connected
     * and its handshake. The connection composition root binds the live graph
     * here (OPE-176); the feature itself never wires L2/L3 surfaces.
     */
    private val onConnected: (ServerProfile, ConnectionHandshake) -> Unit = { _, _ -> },
) {
    private val mutableState = MutableStateFlow(ConnectionSetupUiState())

    /** Observable UI state. */
    public val state: StateFlow<ConnectionSetupUiState> = mutableState.asStateFlow()

    private var lastPlan: ServerSetupPlan? = null

    /** Called as the user types in the address field. */
    public fun onAddressChange(value: String) {
        mutableState.update { it.copy(address = value, manualError = null, scanFailed = false) }
    }

    /** Called as the user types in the optional label field. */
    public fun onLabelChange(value: String) {
        mutableState.update { it.copy(label = value) }
    }

    /** Called as the user types the optional reverse-proxy Basic Auth username. */
    public fun onUsernameChange(value: String) {
        mutableState.update { it.copy(username = value) }
    }

    /** Called as the user types the optional reverse-proxy Basic Auth password. */
    public fun onPasswordChange(value: String) {
        mutableState.update { it.copy(password = value) }
    }

    /** Validates and opens the review screen for what the user typed. */
    public fun submitManualEntry() {
        val current = mutableState.value
        when (val planning = setup.planManualEntry(current.address, current.label.ifBlank { null })) {
            is ServerSetupPlanning.Ready -> openReview(planning.plan)
            is ServerSetupPlanning.Invalid ->
                mutableState.update { it.copy(manualError = planning.error, scanFailed = false) }

            ServerSetupPlanning.Discarded -> Unit
        }
    }

    /**
     * Handles a payload decoded by the QR scanner. A payload that is not a valid
     * import link is discarded silently (T8); [ConnectionSetupUiState.scanFailed]
     * is set only so the scanner surface can indicate that the code was not one
     * of ours.
     *
     * When a review is already pending, the new payload is discarded without
     * replacing the pending one: "one pending import at a time"
     * (`docs/ARCHITECTURE.md` §"Mandatory review before persisting"). The user
     * must re-trigger the import to review the second link.
     */
    public fun submitScannedPayload(payload: String) {
        if (mutableState.value.isReviewing) return
        when (val planning = setup.planImport(payload, ServerSetupSource.QrCode)) {
            is ServerSetupPlanning.Ready -> openReview(planning.plan)
            else -> mutableState.update { it.copy(scanFailed = true, manualError = null) }
        }
    }

    /** Persists the reviewed profile (the "Add server" / "Update server" action). */
    public fun confirmReview() {
        val plan = lastPlan ?: return
        connect(plan)
    }

    /** Dismisses the review screen without persisting anything. */
    public fun cancelReview() {
        lastPlan = null
        mutableState.update {
            it.copy(review = null, existingProfile = null, existingFingerprint = null, identityPrompt = null)
        }
    }

    /**
     * Accepts a first-contact fingerprint: the injected confirmer persists the
     * pin, then the connection is retried, which now passes the identity gate.
     */
    public fun confirmIdentity() {
        val plan = lastPlan ?: return
        val presented = mutableState.value.identityPrompt ?: return
        scope.launch {
            mutableState.update { it.copy(identityPrompt = null, busy = true) }
            try {
                identityConfirmer(plan.profile, presented)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                mutableState.update { it.copy(busy = false, failure = failure.toDomainError().toConnectionFailure()) }
                return@launch
            }
            proceed(plan)
        }
    }

    private fun openReview(plan: ServerSetupPlan) {
        lastPlan = plan
        scope.launch {
            val existing = readOrFail { existingProfileProvider() }.getOrElse { return@launch }
            val pinned = readOrFail { existing?.let { existingFingerprintProvider(it) } }.getOrElse { return@launch }
            // Round-trips a previously-saved reverse-proxy credential back into the
            // UI so reconnecting to the same profile does not require retyping it.
            val savedBasicAuth = readOrFail { existing?.let { basicAuthProvider(it) } }.getOrElse { return@launch }
            mutableState.update {
                it.copy(
                    review = plan,
                    existingProfile = existing,
                    existingFingerprint = pinned,
                    username = savedBasicAuth?.username ?: it.username,
                    password = savedBasicAuth?.password ?: it.password,
                    manualError = null,
                    scanFailed = false,
                    failure = null,
                )
            }
        }
    }

    /**
     * Runs [block], mapping any non-cancellation failure to the failure state
     * and reporting it as [Result.failure] instead of throwing, so a caller
     * reading several providers in sequence (as [openReview] does) only needs
     * one `getOrElse { return@launch }` per read instead of a repeated
     * try/catch.
     */
    private suspend fun <T> readOrFail(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            mutableState.update { it.copy(busy = false, failure = failure.toDomainError().toConnectionFailure()) }
            Result.failure(failure)
        }

    private fun connect(plan: ServerSetupPlan) {
        scope.launch {
            mutableState.update { it.copy(busy = true, failure = null) }
            proceed(plan)
        }
    }

    private suspend fun proceed(plan: ServerSetupPlan) {
        val credential = try {
            credentialProvider(plan.profile)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            mutableState.update { it.copy(busy = false, failure = failure.toDomainError().toConnectionFailure()) }
            return
        }

        // Blank/blank means "no reverse-proxy Basic Auth for this profile": it is
        // never persisted or sent, so clearing both fields is how the user opts
        // out again.
        val username = mutableState.value.username.trim()
        val password = mutableState.value.password
        val basicAuth = if (username.isNotBlank() && password.isNotBlank()) {
            BasicAuthCredential(username, password)
        } else {
            null
        }
        if (basicAuth != null) {
            try {
                basicAuthPersister(plan.profile, basicAuth)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                mutableState.update { it.copy(busy = false, failure = failure.toDomainError().toConnectionFailure()) }
                return
            }
        }

        when (val result = setup.validate(plan, credential, basicAuth)) {
            is ConnectionValidation.Connected -> {
                mutableState.update { it.copy(busy = false, connected = result.handshake, review = null, failure = null) }
                onConnected(plan.profile, result.handshake)
            }

            is ConnectionValidation.Rejected -> when (val error = result.error) {
                is DomainError.IdentityUnconfirmed ->
                    mutableState.update { it.copy(busy = false, identityPrompt = error.presented, failure = null) }

                else -> mutableState.update { it.copy(busy = false, failure = error.toConnectionFailure()) }
            }
        }
    }

    /** Clears the transient failure banner. */
    public fun dismissFailure() {
        mutableState.update { it.copy(failure = null) }
    }
}
