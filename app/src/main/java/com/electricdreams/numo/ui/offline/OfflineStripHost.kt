package com.electricdreams.numo.ui.offline

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.roundToInt

/**
 * Owns one activity's [OfflineStripView].
 *
 * The strip is added straight to the decor view, and the decor's top-level child gets an insets
 * listener that reports a taller status bar while the strip is showing. Screens already pad for
 * the status bar, so they move down under the strip with no per-screen code.
 */
internal class OfflineStripHost(private val activity: Activity, onTap: () -> Unit) {

    private val window = activity.window
    private val decor = window.decorView as ViewGroup
    private val strip = OfflineStripView(activity).apply {
        setOnStripClickListener(onTap)
        readBefore(android.R.id.content)
    }
    private var insetsTarget: View? = null
    private var extraTop = 0
    private var animator: ValueAnimator? = null
    private var savedLightStatusBars: Boolean? = null
    private var savedStatusBarColor: Int? = null

    /** Mode currently on screen, or null when the strip is hidden. */
    private var shownMode: OfflineStripView.Mode? = null

    fun attach() {
        strip.visibility = View.GONE
        decor.addView(
            strip,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        )
        val target = findTopLevelContentView() ?: return
        insetsTarget = target
        ViewCompat.setOnApplyWindowInsetsListener(target) { view, insets ->
            // This decor child always sees the real insets first, on every API level
            strip.setStatusBarInset(insets.getInsets(WindowInsetsCompat.Type.statusBars()).top)
            ViewCompat.onApplyWindowInsets(view, insets.withExtraStatusBarTop(extraTop))
        }
    }

    fun detach() {
        animator?.cancel()
        insetsTarget?.let { ViewCompat.setOnApplyWindowInsetsListener(it, null) }
        decor.removeView(strip)
    }

    fun render(mode: OfflineStripView.Mode?, animate: Boolean) {
        if (mode == shownMode) return
        val wasShown = shownMode != null
        shownMode = mode
        when {
            mode != null && !wasShown -> {
                strip.setMode(mode, animate = false)
                reveal(show = true, animate = animate)
            }
            mode != null -> strip.setMode(mode, animate) { remeasure() }
            wasShown -> reveal(show = false, animate = animate)
        }
    }

    /** Screens may reset status bar icon colors in onResume; keep them light over the strip. */
    @Suppress("DEPRECATION")
    fun onResumed() {
        decor.post {
            if (shownMode == null) return@post
            val controller = WindowCompat.getInsetsController(window, decor)
            if (controller.isAppearanceLightStatusBars) {
                savedLightStatusBars = true
                controller.isAppearanceLightStatusBars = false
            }
            if (window.statusBarColor != Color.TRANSPARENT) {
                savedStatusBarColor = window.statusBarColor
                window.statusBarColor = Color.TRANSPARENT
            }
        }
    }

    private fun reveal(show: Boolean, animate: Boolean) {
        animator?.cancel()
        animator = null
        val width = decor.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
        strip.measureContentHeight(width)
        if (show) {
            strip.visibility = View.VISIBLE
            adaptStatusBar(stripVisible = true)
        }
        val target = if (show) 1f else 0f
        if (!animate) {
            applyReveal(target)
            if (!show) finishHide()
            return
        }
        animator = ValueAnimator.ofFloat(strip.reveal, target).apply {
            duration = if (show) SHOW_DURATION_MS else HIDE_DURATION_MS
            interpolator = EASE_OUT
            addUpdateListener { applyReveal(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!show && !cancelled) finishHide()
                }
            })
            start()
        }
    }

    /** Offline and Back-online text can wrap to different line counts; keep the gap honest. */
    private fun remeasure() {
        if (shownMode == null || animator?.isRunning == true) return
        val width = decor.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
        strip.measureContentHeight(width)
        applyReveal(strip.reveal)
    }

    private fun applyReveal(progress: Float) {
        strip.reveal = progress
        val newExtraTop = (strip.contentHeight * progress).roundToInt()
        if (newExtraTop != extraTop) {
            extraTop = newExtraTop
            ViewCompat.requestApplyInsets(decor)
        }
    }

    private fun finishHide() {
        strip.visibility = View.GONE
        adaptStatusBar(stripVisible = false)
    }

    /**
     * Light icons over the dark strip. Below Android 15 a screen's status bar color is drawn as a
     * view on top of the strip, so it goes transparent while the strip shows; both are restored.
     */
    @Suppress("DEPRECATION")
    private fun adaptStatusBar(stripVisible: Boolean) {
        val controller = WindowCompat.getInsetsController(window, decor)
        if (stripVisible) {
            if (savedLightStatusBars == null) {
                savedLightStatusBars = controller.isAppearanceLightStatusBars
            }
            if (savedStatusBarColor == null) {
                savedStatusBarColor = window.statusBarColor
            }
            controller.isAppearanceLightStatusBars = false
            window.statusBarColor = Color.TRANSPARENT
        } else {
            savedLightStatusBars?.let { controller.isAppearanceLightStatusBars = it }
            savedStatusBarColor?.let { window.statusBarColor = it }
            savedLightStatusBars = null
            savedStatusBarColor = null
        }
    }

    /** The decor child that wraps `android.R.id.content`; screens own listeners below it. */
    private fun findTopLevelContentView(): View? {
        var view: View = activity.findViewById(android.R.id.content) ?: return null
        while (true) {
            val parent = view.parent as? View ?: return null
            if (parent === decor) return view
            view = parent
        }
    }

    companion object {
        private const val SHOW_DURATION_MS = 320L
        private const val HIDE_DURATION_MS = 280L
        private val EASE_OUT = PathInterpolator(0.32f, 0.72f, 0f, 1f)
    }
}
