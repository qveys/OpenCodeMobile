package org.opencodemobile.features.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.opencodemobile.shared.domain.localaccess.LocalAccessSettings
import org.opencodemobile.shared.domain.localaccess.LocalAccessSettingsStore

/**
 * The §7.3 local-access settings presenter.
 *
 * It owns the current [LocalAccessSettings], persists every change through the
 * domain [LocalAccessSettingsStore] port, and exposes the value to the Compose
 * layer. The initial state is the persisted value (or
 * [LocalAccessSettings.Default] on a fresh install), so the defaults are decided
 * in the domain, not duplicated here.
 *
 * It is deliberately thin and platform-free: the platform store is injected, so
 * this class is covered by a common unit test on a fake store.
 */
public class LocalAccessSettingsController(
    private val store: LocalAccessSettingsStore,
) {
    private val mutableState = MutableStateFlow(store.load())

    /** The current settings; [LocalAccessSettings.Default] on a fresh install. */
    public val state: StateFlow<LocalAccessSettings> = mutableState.asStateFlow()

    /** Enables or disables the optional local biometric re-authentication. */
    public fun setOptionalBiometricsEnabled(enabled: Boolean): Unit =
        update { it.copy(optionalBiometricsEnabled = enabled) }

    /** Enables or disables the app-switcher preview masking (on by default). */
    public fun setMultitaskMaskingEnabled(enabled: Boolean): Unit =
        update { it.copy(multitaskMaskingEnabled = enabled) }

    /** Enables or disables the OS screen-capture blocking (off by default). */
    public fun setScreenCaptureBlockingEnabled(enabled: Boolean): Unit =
        update { it.copy(screenCaptureBlockingEnabled = enabled) }

    private fun update(transform: (LocalAccessSettings) -> LocalAccessSettings) {
        val next = transform(mutableState.value)
        store.save(next)
        mutableState.value = next
    }
}
