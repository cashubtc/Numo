package com.electricdreams.numo.ui.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * Centralized helper for haptic feedback.
 *
 * Goals:
 * - Use modern VibrationEffect APIs where available.
 * - Contain any legacy vibrate(long) usage in one place.
 * - Gracefully no-op on devices without a vibrator.
 */
object VibrationHelper {

    private const val DEFAULT_CLICK_MS = 20L

    /**
     * Perform a short, click-like vibration suitable for key presses.
     */
    fun performClick(context: Context, durationMs: Long = DEFAULT_CLICK_MS) {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            ?: return

        // Avoid crashing on devices without a vibrator
        if (!vibrator.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // API 26+: VibrationEffect is the modern API
            val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
            } else {
                VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
            }
            vibrator.vibrate(effect)
        } else {
            // Legacy fallback for very old devices, contained here only
            @Suppress("DEPRECATION")
            vibrator.vibrate(durationMs)
        }
    }
}
