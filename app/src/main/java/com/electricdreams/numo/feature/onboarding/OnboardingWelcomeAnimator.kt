package com.electricdreams.numo.feature.onboarding

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.res.ColorStateList
import android.view.View
import android.view.ViewAnimationUtils
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.electricdreams.numo.R
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.hypot

/**
 * Cinematic welcome screen animation:
 * 1. Circular reveal (blank white → navy, expanding from screen center)
 * 2. Staggered per-letter NUMO reveal (white on navy)
 * 3. Tagline fades in
 * 4. Get Started button + terms fade in
 */
class OnboardingWelcomeAnimator(
    private val activity: Activity,
    private val container: View,
    private val letterViews: List<ImageView>,
    private val letterContainer: android.widget.LinearLayout,
    private val tagline: TextView,
    private val acceptButton: MaterialButton,
    private val termsText: TextView,
    private val revealView: View
) {
    private val navyColor = ContextCompat.getColor(activity, R.color.numo_navy)
    private val whiteColor = ContextCompat.getColor(activity, android.R.color.white)

    private val activeAnimators = mutableListOf<Animator>()
    private var systemBarsFlipped = false

    fun start(scope: CoroutineScope) {
        stop()
        resetAllViews()

        container.post {
            scope.launch {
                delay(300) // Brief pause on blank white
                startPhase1_CircularReveal()
                delay(200)
                startPhase2_LogoReveal()
                startPhase3_Tagline()
                delay(400)
                startPhase4_CtaReveal()
            }
        }
    }

    fun stop() {
        val animators = activeAnimators.toList()
        activeAnimators.clear()
        animators.forEach { it.cancel() }
        systemBarsFlipped = false
    }

    fun pause() {
        activeAnimators.toList().forEach { it.pause() }
    }

    fun resume() {
        activeAnimators.toList().forEach { it.resume() }
    }

    private fun resetAllViews() {
        container.findViewById<View>(R.id.welcome_background_overlay)
            ?.setBackgroundColor(whiteColor)

        revealView.visibility = View.INVISIBLE

        val density = activity.resources.displayMetrics.density
        letterViews.forEach { letter ->
            letter.imageTintList = ColorStateList.valueOf(whiteColor)
            letter.alpha = 0f
            letter.translationY = 24f * density
            letter.scaleX = 0.9f
            letter.scaleY = 0.9f
        }

        tagline.setTextColor(whiteColor)
        tagline.alpha = 0f
        tagline.translationY = 15f

        acceptButton.alpha = 0f
        acceptButton.translationY = 20f
        termsText.alpha = 0f
        termsText.translationY = 10f

        activity.window.statusBarColor = whiteColor
        activity.window.navigationBarColor = whiteColor
        val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
        controller.isAppearanceLightStatusBars = true
        controller.isAppearanceLightNavigationBars = true
        systemBarsFlipped = false
    }

    // === Phase 1: Circular Reveal (white → navy, ~1200ms) ===
    // Navy overlay expands from screen center. Status/nav bar colors flip to navy
    // only once the circle has actually reached the top/bottom edges.

    private suspend fun startPhase1_CircularReveal() = suspendCancellableCoroutine<Unit> { cont ->
        val centerX = revealView.width / 2
        val centerY = revealView.height / 2

        val maxRadius = hypot(centerX.toDouble(), centerY.toDouble()).toFloat()

        // Distance from center to the top and bottom edges of the screen
        val distToTop = centerY.toFloat()
        val distToBottom = (revealView.height - centerY).toFloat()
        // Fraction of the animation at which the circle reaches each edge
        val topThreshold = distToTop / maxRadius
        val bottomThreshold = distToBottom / maxRadius

        revealView.visibility = View.VISIBLE

        var statusBarFlipped = false
        var navBarFlipped = false

        val reveal = ViewAnimationUtils.createCircularReveal(
            revealView, centerX, centerY, 0f, maxRadius
        ).apply {
            duration = 1200
            interpolator = AccelerateDecelerateInterpolator()

            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    container.findViewById<View>(R.id.welcome_background_overlay)
                        ?.setBackgroundColor(navyColor)
                    activity.window.statusBarColor = navyColor
                    activity.window.navigationBarColor = navyColor
                    val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                    controller.isAppearanceLightStatusBars = false
                    controller.isAppearanceLightNavigationBars = false
                    systemBarsFlipped = true
                    if (cont.isActive) cont.resume(Unit)
                }
            })
        }

        // Parallel animator to track progress and flip bar colors at the right moment
        val barTracker = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1200
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val fraction = it.animatedFraction

                if (!statusBarFlipped && fraction >= topThreshold) {
                    statusBarFlipped = true
                    activity.window.statusBarColor = navyColor
                    val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                    controller.isAppearanceLightStatusBars = false
                }

                if (!navBarFlipped && fraction >= bottomThreshold) {
                    navBarFlipped = true
                    activity.window.navigationBarColor = navyColor
                    val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                    controller.isAppearanceLightNavigationBars = false
                }
            }
        }

        cont.invokeOnCancellation {
            reveal.cancel()
            barTracker.cancel()
        }
        trackAndStart(reveal)
        trackAndStart(barTracker)
    }

    // === Phase 2: Staggered Per-Letter Reveal (~720ms) ===

    private val appleSpring = android.view.animation.PathInterpolator(0.175f, 0.885f, 0.32f, 1.1f)

    private suspend fun startPhase2_LogoReveal() = suspendCancellableCoroutine<Unit> { cont ->
        val density = activity.resources.displayMetrics.density
        val staggerDelay = 90L
        val letterDuration = 450L
        var completedCount = 0
        val phaseAnimators = mutableListOf<Animator>()

        letterViews.forEachIndexed { index, letter ->
            val animSet = AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(letter, "alpha", 0f, 1f),
                    ObjectAnimator.ofFloat(letter, "translationY", 24f * density, 0f),
                    ObjectAnimator.ofFloat(letter, "scaleX", 0.9f, 1f),
                    ObjectAnimator.ofFloat(letter, "scaleY", 0.9f, 1f)
                )
                duration = letterDuration
                startDelay = index * staggerDelay
                interpolator = appleSpring
            }

            animSet.addListener(onEnd {
                completedCount++
                if (completedCount == letterViews.size && cont.isActive) {
                    cont.resume(Unit)
                }
            })
            phaseAnimators.add(animSet)
            trackAndStart(animSet)
        }
        cont.invokeOnCancellation { phaseAnimators.forEach { it.cancel() } }
    }

    // === Phase 3: Tagline (450ms) ===

    private suspend fun startPhase3_Tagline() = suspendCancellableCoroutine<Unit> { cont ->
        val animSet = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(tagline, "alpha", 0f, 1f),
                ObjectAnimator.ofFloat(tagline, "translationY", 15f, 0f)
            )
            duration = 450
            interpolator = DecelerateInterpolator()
            addListener(onEnd { if (cont.isActive) cont.resume(Unit) })
        }
        cont.invokeOnCancellation { animSet.cancel() }
        trackAndStart(animSet)
    }

    // === Phase 4: CTA Reveal (500ms) ===

    private suspend fun startPhase4_CtaReveal() = suspendCancellableCoroutine<Unit> { cont ->
        val btnAlpha = ObjectAnimator.ofFloat(acceptButton, "alpha", 0f, 1f).apply {
            duration = 400
            interpolator = DecelerateInterpolator()
        }
        val btnTranslate = ObjectAnimator.ofFloat(acceptButton, "translationY", 20f, 0f).apply {
            duration = 400
            interpolator = DecelerateInterpolator()
        }

        val termsAlpha = ObjectAnimator.ofFloat(termsText, "alpha", 0f, 0.7f).apply {
            duration = 350
            startDelay = 200
            interpolator = DecelerateInterpolator()
        }
        val termsTranslate = ObjectAnimator.ofFloat(termsText, "translationY", 10f, 0f).apply {
            duration = 350
            startDelay = 200
            interpolator = DecelerateInterpolator()
        }

        val animSet = AnimatorSet().apply {
            playTogether(btnAlpha, btnTranslate, termsAlpha, termsTranslate)
            addListener(onEnd { if (cont.isActive) cont.resume(Unit) })
        }
        cont.invokeOnCancellation { animSet.cancel() }
        trackAndStart(animSet)
    }

    // === Helpers ===

    private fun onEnd(action: () -> Unit): AnimatorListenerAdapter {
        return object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                action()
            }
        }
    }

    private fun trackAndStart(animator: Animator) {
        activeAnimators.add(animator)
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                activeAnimators.remove(animation)
            }
        })
        animator.start()
    }
}
