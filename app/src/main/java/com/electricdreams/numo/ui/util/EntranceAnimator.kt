package com.electricdreams.numo.ui.util

import android.view.View
import android.view.animation.DecelerateInterpolator

object EntranceAnimator {

    private const val STAGGER_DELAY = 50L
    private const val DURATION = 400L
    private const val TRANSLATE_Y_DP = 16f

    fun animateEntrance(views: List<View>, baseDelay: Long = 100L) {
        val density = if (views.isNotEmpty()) views[0].resources.displayMetrics.density else 1f
        val translateY = TRANSLATE_Y_DP * density
        views.forEachIndexed { index, view ->
            view.alpha = 0f
            view.translationY = translateY
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(baseDelay + index * STAGGER_DELAY)
                .setDuration(DURATION)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    fun animateSingle(view: View, delay: Long = 100L) {
        val density = view.resources.displayMetrics.density
        val translateY = TRANSLATE_Y_DP * density
        view.alpha = 0f
        view.translationY = translateY
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(delay)
            .setDuration(DURATION)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }
}
