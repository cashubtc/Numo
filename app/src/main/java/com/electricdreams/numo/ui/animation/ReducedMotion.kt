package com.electricdreams.numo.ui.animation

import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * Whether the user turned animations off (Settings › Accessibility › Remove animations).
 * When true, motion should jump straight to its final state.
 */
object ReducedMotion {
    fun isEnabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return !ValueAnimator.areAnimatorsEnabled()
        }
        val scale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
        return scale == 0f
    }
}
