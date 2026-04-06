package com.electricdreams.numo.feature.onboarding

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.view.View
import android.view.ViewAnimationUtils
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.electricdreams.numo.R
import com.electricdreams.numo.ui.util.isAnimationEnabled
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
    private val revealView: View,
    private val emojiContainer: FrameLayout
) {
    private val navyColor = ContextCompat.getColor(activity, R.color.numo_navy)
    private val whiteColor = ContextCompat.getColor(activity, android.R.color.white)

    private val activeAnimators = mutableListOf<Animator>()

    // === Scrolling row tiles ===
    private data class RowTile(val emoji: String, val colorRes: Int)
    private val row1Emojis = listOf(RowTile("\uD83D\uDC55", R.color.chip_ribbon_cyan), RowTile("\uD83E\uDD69", R.color.chip_ribbon_pink), RowTile("\uD83C\uDF3F", R.color.chip_ribbon_lime), RowTile("\uD83E\uDD5C", R.color.chip_ribbon_green), RowTile("\u2615", R.color.chip_ribbon_orange), RowTile("\uD83C\uDFB8", R.color.chip_ribbon_cyan), RowTile("\uD83E\uDDE2", R.color.chip_ribbon_purple), RowTile("\uD83D\uDCF1", R.color.chip_ribbon_yellow))
    private val row2Emojis = listOf(RowTile("\uD83C\uDF55", R.color.chip_ribbon_orange), RowTile("\uD83E\uDDC1", R.color.chip_ribbon_pink), RowTile("\uD83D\uDC8E", R.color.chip_ribbon_purple), RowTile("\uD83C\uDFA8", R.color.chip_ribbon_cyan), RowTile("\uD83C\uDF2E", R.color.chip_ribbon_yellow), RowTile("\uD83C\uDF77", R.color.chip_ribbon_pink), RowTile("\uD83E\uDDF5", R.color.chip_ribbon_lime), RowTile("\uD83C\uDFAA", R.color.chip_ribbon_green))
    private val row3Emojis = listOf(RowTile("\uD83E\uDD56", R.color.chip_ribbon_yellow), RowTile("\uD83E\uDDC0", R.color.chip_ribbon_orange), RowTile("\uD83C\uDF3A", R.color.chip_ribbon_pink), RowTile("\uD83D\uDCE6", R.color.chip_ribbon_green), RowTile("\uD83C\uDF70", R.color.chip_ribbon_purple), RowTile("\uD83C\uDF73", R.color.chip_ribbon_lime), RowTile("\uD83D\uDECD\uFE0F", R.color.chip_ribbon_cyan), RowTile("\uD83C\uDF7A", R.color.chip_ribbon_orange))
    private val row4Emojis = listOf(RowTile("\uD83E\uDDF4", R.color.chip_ribbon_cyan), RowTile("\uD83C\uDF4B", R.color.chip_ribbon_yellow), RowTile("\uD83C\uDFA7", R.color.chip_ribbon_purple), RowTile("\uD83E\uDDCA", R.color.chip_ribbon_lime), RowTile("\uD83C\uDF6B", R.color.chip_ribbon_orange), RowTile("\uD83C\uDFB2", R.color.chip_ribbon_green), RowTile("\uD83E\uDDF8", R.color.chip_ribbon_pink), RowTile("\uD83D\uDD11", R.color.chip_ribbon_cyan))
    private val row5Emojis = listOf(RowTile("\uD83C\uDF81", R.color.chip_ribbon_pink), RowTile("\uD83E\uDD64", R.color.chip_ribbon_orange), RowTile("\uD83E\uDDF2", R.color.chip_ribbon_purple), RowTile("\uD83C\uDF7F", R.color.chip_ribbon_yellow), RowTile("\uD83E\uDDF3", R.color.chip_ribbon_green), RowTile("\uD83C\uDFAF", R.color.chip_ribbon_cyan), RowTile("\uD83C\uDF69", R.color.chip_ribbon_lime), RowTile("\uD83D\uDD2E", R.color.chip_ribbon_purple))

    private data class ScrollingTile(val view: View, val initialX: Float, val speedPx: Float, val direction: Float, val wrapWidth: Float, val rowY: Float, val targetAlpha: Float)
    private val scrollingTiles = mutableListOf<ScrollingTile>()
    private var scrollAnimator: ValueAnimator? = null
    private var scrollTime = 0f
    private var rowGradientView: View? = null
    private var systemBarsFlipped = false

    fun start(scope: CoroutineScope) {
        stop()
        resetAllViews()

        if (!activity.isAnimationEnabled()) {
            // Skip to final state: everything visible
            showFinalState()
            return
        }

        container.post {
            scope.launch {
                delay(300) // Brief pause on blank white
                startPhase1_CircularReveal()
                delay(200)
                startPhase2_LogoReveal()
                startPhase3_Tagline()
                startScrollingRows()
                delay(400)
                startPhase4_CtaReveal()
            }
        }
    }

    private fun showFinalState() {
        revealView.visibility = View.VISIBLE
        letterViews.forEach { it.alpha = 1f; it.translationY = 0f; it.scaleX = 1f; it.scaleY = 1f }
        tagline.alpha = 1f; tagline.translationY = 0f
        acceptButton.alpha = 1f; acceptButton.translationY = 0f
        termsText.alpha = 0.7f; termsText.translationY = 0f
        startScrollingRowsFinalState()
        val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        systemBarsFlipped = true
    }

    fun stop() {
        val animators = activeAnimators.toList()
        activeAnimators.clear()
        animators.forEach { it.cancel() }
        scrollAnimator?.cancel()
        scrollAnimator = null
        scrollTime = 0f
        scrollingTiles.clear()
        rowGradientView = null
        emojiContainer.removeAllViews()
        systemBarsFlipped = false
    }

    fun pause() {
        scrollAnimator?.pause()
        activeAnimators.toList().forEach { it.pause() }
    }

    fun resume() {
        scrollAnimator?.resume()
        activeAnimators.toList().forEach { it.resume() }
    }

    private fun resetAllViews() {
        container.findViewById<View>(R.id.welcome_background_overlay)
            ?.setBackgroundColor(whiteColor)

        revealView.visibility = View.INVISIBLE
        emojiContainer.removeAllViews()
        scrollingTiles.clear()
        scrollAnimator?.cancel()
        scrollAnimator = null
        scrollTime = 0f

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

    // === Phase 1: Circular Reveal (white → navy, ~800ms) ===
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
            duration = 800
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
            duration = 800
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
            interpolator = appleSpring
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


    private fun startScrollingRowsFinalState() {
        createScrollingRows()
        startScrollAnimation()
        scrollingTiles.forEach { tile -> tile.view.alpha = tile.targetAlpha }
        rowGradientView?.alpha = 1f
    }

    private suspend fun startScrollingRows() {
        createScrollingRows()
        startScrollAnimation()

        suspendCancellableCoroutine<Unit> { cont ->
            val fadeAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 800
                interpolator = DecelerateInterpolator()

                addUpdateListener {
                    val fraction = it.animatedFraction
                    scrollingTiles.forEach { tile -> tile.view.alpha = tile.targetAlpha * fraction }
                    rowGradientView?.alpha = fraction
                }

                addListener(onEnd { if (cont.isActive) cont.resume(Unit) })
            }
            cont.invokeOnCancellation { fadeAnim.cancel() }
            trackAndStart(fadeAnim)
        }
    }

    private fun createScrollingRows() {
        val density = activity.resources.displayMetrics.density
        val tileSizePx = (48 * density).toInt()
        val spacingPx = (8 * density).toInt()
        val stepPx = tileSizePx + spacingPx

        data class RowConfig(
            val emojis: List<RowTile>,
            val direction: Float,  // 1.0 = R→L, -1.0 = L→R
            val speedDp: Float,
            val rowIndex: Int,
            val alpha: Float       // target opacity for this row
        )

        val rows = listOf(
            RowConfig(row1Emojis,  1f, 12f, 0, 0.22f),
            RowConfig(row2Emojis, -1f,  9f, 1, 0.16f),
            RowConfig(row3Emojis,  1f, 15f, 2, 0.11f),
            RowConfig(row4Emojis, -1f,  7f, 3, 0.06f),
            RowConfig(row5Emojis,  1f, 11f, 4, 0.03f)
        )

        scrollingTiles.clear()

        rows.forEach { config ->
            val wrapWidth = (config.emojis.size * stepPx).toFloat()
            val speedPx = config.speedDp * density
            val rowY = (config.rowIndex * stepPx).toFloat()

            config.emojis.forEachIndexed { i, item ->
                val tileView = createRowTileView(item, sizePx = tileSizePx)
                tileView.translationY = rowY
                tileView.alpha = 0f
                emojiContainer.addView(tileView)

                scrollingTiles.add(ScrollingTile(
                    view = tileView,
                    initialX = (i * stepPx).toFloat(),
                    speedPx = speedPx,
                    direction = config.direction,
                    wrapWidth = wrapWidth,
                    rowY = rowY,
                    targetAlpha = config.alpha
                ))
            }
        }

        // Gradient overlay — aggressive fade so bottom rows dissolve into background
        val totalRowsHeight = 5 * tileSizePx + 4 * spacingPx
        val gradientHeight = (totalRowsHeight * 0.8f).toInt()
        rowGradientView = View(activity).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, Color.argb(220, 10, 37, 64), navyColor)
            )
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                gradientHeight
            ).apply {
                gravity = Gravity.TOP
                topMargin = totalRowsHeight - gradientHeight
            }
            alpha = 0f
        }
        emojiContainer.addView(rowGradientView)
    }

    private fun startScrollAnimation() {
        scrollTime = 0f
        scrollAnimator?.cancel()

        scrollAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 16L  // ~60fps tick
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                scrollTime += 0.016f
                updateRowPositions()
            }
        }
        scrollAnimator?.start()
    }

    private fun updateRowPositions() {
        val containerWidth = emojiContainer.width.toFloat()

        scrollingTiles.forEach { tile ->
            val totalScroll = scrollTime * tile.speedPx
            // Modulo wrap: tile scrolls continuously and wraps around
            var x = tile.initialX - totalScroll * tile.direction
            x = ((x % tile.wrapWidth) + tile.wrapWidth) % tile.wrapWidth
            // Shift so tiles cover the visible area (centered around 0..containerWidth)
            if (x > containerWidth) x -= tile.wrapWidth
            tile.view.translationX = x
        }
    }

    private fun createRowTileView(tile: RowTile, sizePx: Int): View {
        return TextView(activity).apply {
            text = tile.emoji
            textSize = 22f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
        }
    }

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
