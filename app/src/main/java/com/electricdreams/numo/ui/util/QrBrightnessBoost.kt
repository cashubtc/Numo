package com.electricdreams.numo.ui.util

import android.content.Context
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner

import com.electricdreams.numo.core.prefs.PreferenceStore

/**
 * Turns the screen up to full brightness while a QR code is on screen, so a customer's camera
 * can read it in sunlight or from a dim display.
 *
 * Only this activity's window brightness is overridden, so no permission is needed and the
 * system setting is never touched. The boost is dropped as soon as the activity pauses
 * (exit, share sheet, card tap screen, app switch) or the QR is covered, and comes back when
 * the QR is visible again.
 */
class QrBrightnessBoost(private val activity: ComponentActivity) : DefaultLifecycleObserver {

    private var qrVisible = true

    /** Whether the window is currently held at full brightness. */
    var isBoosting: Boolean = false
        private set

    init {
        activity.lifecycle.addObserver(this)
    }

    /** Call when the QR is covered (overlay, result screen) or shown again. */
    fun setQrVisible(visible: Boolean) {
        if (qrVisible == visible) return
        qrVisible = visible
        update()
    }

    /** Flips the saved preference, applies it right away, and returns the new value. */
    fun toggle(): Boolean {
        val enabled = !isEnabled(activity)
        setEnabled(activity, enabled)
        update()
        return enabled
    }

    override fun onResume(owner: LifecycleOwner) = update()

    override fun onPause(owner: LifecycleOwner) = setBoost(false)

    override fun onDestroy(owner: LifecycleOwner) {
        owner.lifecycle.removeObserver(this)
    }

    private fun update() {
        val resumed = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        setBoost(resumed && qrVisible && isEnabled(activity))
    }

    private fun setBoost(boost: Boolean) {
        if (isBoosting == boost) return
        isBoosting = boost
        val window = activity.window
        val params = window.attributes
        params.screenBrightness = if (boost) {
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
        } else {
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        window.attributes = params
        Log.d(TAG, "QR brightness boost ${if (boost) "on" else "off"}")
    }

    companion object {
        private const val TAG = "QrBrightnessBoost"
        const val PREF_KEY = "qr_brightness_boost"

        fun isEnabled(context: Context): Boolean =
            PreferenceStore.app(context).getBoolean(PREF_KEY, true)

        fun setEnabled(context: Context, enabled: Boolean) {
            PreferenceStore.app(context).putBoolean(PREF_KEY, enabled)
        }
    }
}
