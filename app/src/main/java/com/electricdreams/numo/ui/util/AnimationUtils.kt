package com.electricdreams.numo.ui.util

import android.animation.ObjectAnimator
import android.content.Context
import android.provider.Settings
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Checks whether the system animation scale allows animations to run.
 * Returns false when the user has disabled animations via Developer Options
 * ("Remove animations" / animator duration scale = 0).
 */
fun Context.isAnimationEnabled(): Boolean {
    val scale = Settings.Global.getFloat(
        contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
    )
    return scale > 0f
}

/**
 * Unified shake animation for error feedback.
 * Uses ObjectAnimator with decelerating keyframes for natural damping
 * (sharp start, gentle end).
 */
fun View.shake(amplitude: Float = 12f, duration: Long = 400L) {
    ObjectAnimator.ofFloat(
        this, "translationX",
        0f, -amplitude, amplitude, -amplitude, amplitude,
        -amplitude * 0.6f, amplitude * 0.6f, -amplitude * 0.3f, amplitude * 0.3f, 0f
    ).apply {
        this.duration = duration
        interpolator = DecelerateInterpolator()
        start()
    }
}
