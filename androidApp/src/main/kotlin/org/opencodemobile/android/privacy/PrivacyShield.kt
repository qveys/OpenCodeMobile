package org.opencodemobile.android.privacy

import android.app.Activity
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import org.opencodemobile.shared.domain.localaccess.LocalAccessSettingsStore

/**
 * Android multitask-preview masking and optional capture blocking (§7.3).
 *
 * The two protections are deliberately separate:
 *
 * - **Masking (default on).** An opaque [View] is laid over the activity's
 *   content root in `onPause`, so the snapshot the OS takes when the app moves
 *   to the app switcher is a blank cover, not the transcript. It is removed in
 *   `onResume`. This is a UI overlay only; it does **not** block screenshots.
 * - **Capture blocking (default off).** Only when the user explicitly enabled it
 *   does the window get `FLAG_SECURE`. On Android `FLAG_SECURE` also blocks
 *   screenshots and screen recording, which is why it must never be turned on as
 *   a side effect of masking.
 *
 * The activity owns the lifecycle calls; this class only holds the state and the
 * cover view.
 *
 * @param activity the activity whose window is protected.
 * @param settings the device-local preferences; re-read on every transition so a
 *   change in the settings screen takes effect immediately.
 */
internal class PrivacyShield(
    private val activity: Activity,
    private val settings: LocalAccessSettingsStore,
) {
    private var cover: View? = null

    /** Removes the cover and (re)applies the capture policy. */
    fun onResume() {
        removeCover()
        applyCapturePolicy()
    }

    /** Covers the content before the OS snapshots the activity. */
    fun onPause() {
        if (settings.load().multitaskMaskingEnabled) addCover()
    }

    /** Applies the capture policy now; also called when the setting changes. */
    fun applyCapturePolicy() {
        if (settings.load().screenCaptureBlockingEnabled) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun addCover() {
        if (cover != null) return
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val view = View(activity).apply { setBackgroundColor(Color.BLACK) }
        root.addView(
            view,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        cover = view
    }

    private fun removeCover() {
        val view = cover ?: return
        (view.parent as? ViewGroup)?.removeView(view)
        cover = null
    }
}
