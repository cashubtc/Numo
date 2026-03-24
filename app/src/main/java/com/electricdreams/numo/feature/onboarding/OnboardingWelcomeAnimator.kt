package com.electricdreams.numo.feature.onboarding

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
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
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Cinematic welcome screen animation:
 * 1. Logo reveal (navy on white, centered)
 * 2. Tagline fades in
 * 3. Circle expansion (emojis form circle then burst outward)
 * 4. Color transition (white → navy)
 * 5. Scrolling emoji rows fade in
 * 6. Get Started button + terms fade in
 */
class OnboardingWelcomeAnimator(
    private val activity: Activity,
    private val container: FrameLayout,
    private val wordmark: ImageView,
    private val tagline: TextView,
    private val acceptButton: MaterialButton,
    private val termsText: TextView,
    private val emojiContainer: FrameLayout
) {
    private val navyColor = ContextCompat.getColor(activity, R.color.numo_navy)
    private val whiteColor = ContextCompat.getColor(activity, android.R.color.white)

    // === Circle expansion tiles (mixed sizes) ===

    private enum class TileSize { SMALL, MEDIUM, LARGE }

    private data class CircleTile(val emoji: String, val colorRes: Int, val size: TileSize)

    private val circleTileData = listOf(
        CircleTile("\uD83D\uDC55", R.color.chip_ribbon_cyan, TileSize.LARGE),
        CircleTile("\uD83E\uDD69", R.color.chip_ribbon_pink, TileSize.MEDIUM),
        CircleTile("\uD83C\uDF3F", R.color.chip_ribbon_lime, TileSize.SMALL),
        CircleTile("\uD83E\uDD5C", R.color.chip_ribbon_green, TileSize.LARGE),
        CircleTile("\uD83D\uDCB5", R.color.chip_ribbon_purple, TileSize.MEDIUM),
        CircleTile("\uD83D\uDC2E", R.color.chip_ribbon_yellow, TileSize.SMALL),
        CircleTile("\u2615", R.color.chip_ribbon_orange, TileSize.MEDIUM),
        CircleTile("\uD83C\uDFB8", R.color.chip_ribbon_cyan, TileSize.SMALL)
    )

    // === Scrolling row tiles ===

    private data class RowTile(val emoji: String, val colorRes: Int)

    private val row1Emojis = listOf(
        RowTile("\uD83D\uDC55", R.color.chip_ribbon_cyan),
        RowTile("\uD83E\uDD69", R.color.chip_ribbon_pink),
        RowTile("\uD83C\uDF3F", R.color.chip_ribbon_lime),
        RowTile("\uD83E\uDD5C", R.color.chip_ribbon_green),
        RowTile("\u2615", R.color.chip_ribbon_orange),
        RowTile("\uD83C\uDFB8", R.color.chip_ribbon_cyan),
        RowTile("\uD83E\uDDE2", R.color.chip_ribbon_purple),
        RowTile("\uD83D\uDCF1", R.color.chip_ribbon_yellow)
    )

    private val row2Emojis = listOf(
        RowTile("\uD83C\uDF55", R.color.chip_ribbon_orange),
        RowTile("\uD83E\uDDC1", R.color.chip_ribbon_pink),
        RowTile("\uD83D\uDC8E", R.color.chip_ribbon_purple),
        RowTile("\uD83C\uDFA8", R.color.chip_ribbon_cyan),
        RowTile("\uD83C\uDF2E", R.color.chip_ribbon_yellow),
        RowTile("\uD83C\uDF77", R.color.chip_ribbon_pink),
        RowTile("\uD83E\uDDF5", R.color.chip_ribbon_lime),
        RowTile("\uD83C\uDFAA", R.color.chip_ribbon_green)
    )

    private val row3Emojis = listOf(
        RowTile("\uD83E\uDD56", R.color.chip_ribbon_yellow),
        RowTile("\uD83E\uDDC0", R.color.chip_ribbon_orange),
        RowTile("\uD83C\uDF3A", R.color.chip_ribbon_pink),
        RowTile("\uD83D\uDCE6", R.color.chip_ribbon_green),
        RowTile("\uD83C\uDF70", R.color.chip_ribbon_purple),
        RowTile("\uD83C\uDF73", R.color.chip_ribbon_lime),
        RowTile("\uD83D\uDECD\uFE0F", R.color.chip_ribbon_cyan),
        RowTile("\uD83C\uDF7A", R.color.chip_ribbon_orange)
    )

    private val row4Emojis = listOf(
        RowTile("\uD83E\uDDF4", R.color.chip_ribbon_cyan),
        RowTile("\uD83C\uDF4B", R.color.chip_ribbon_yellow),
        RowTile("\uD83C\uDFA7", R.color.chip_ribbon_purple),
        RowTile("\uD83E\uDDCA", R.color.chip_ribbon_lime),
        RowTile("\uD83C\uDF6B", R.color.chip_ribbon_orange),
        RowTile("\uD83C\uDFB2", R.color.chip_ribbon_green),
        RowTile("\uD83E\uDDF8", R.color.chip_ribbon_pink),
        RowTile("\uD83D\uDD11", R.color.chip_ribbon_cyan)
    )

    private val row5Emojis = listOf(
        RowTile("\uD83C\uDF81", R.color.chip_ribbon_pink),
        RowTile("\uD83E\uDD64", R.color.chip_ribbon_orange),
        RowTile("\uD83E\uDDF2", R.color.chip_ribbon_purple),
        RowTile("\uD83C\uDF7F", R.color.chip_ribbon_yellow),
        RowTile("\uD83E\uDDF3", R.color.chip_ribbon_green),
        RowTile("\uD83C\uDFAF", R.color.chip_ribbon_cyan),
        RowTile("\uD83C\uDF69", R.color.chip_ribbon_lime),
        RowTile("\uD83D\uDD2E", R.color.chip_ribbon_purple)
    )

    // === State ===

    private val activeAnimators = mutableListOf<Animator>()
    private val circleTiles = mutableListOf<View>()
    private val scrollingTiles = mutableListOf<ScrollingTile>()
    private var scrollAnimator: ValueAnimator? = null
    private var scrollTime = 0f
    private var rowGradientView: View? = null
    private var systemBarsFlipped = false

    private data class ScrollingTile(
        val view: View,
        val initialX: Float,
        val speedPx: Float,
        val direction: Float,  // 1.0 for R→L, -1.0 for L→R
        val wrapWidth: Float,
        val rowY: Float,
        val targetAlpha: Float // per-row opacity
    )

    fun start(scope: CoroutineScope) {
        stop()
        resetAllViews()

        container.post {
            scope.launch {
                startPhase1_LogoReveal()
                startPhase2_Tagline()
                delay(200)
                startPhase3_CircleExpansion()
                startPhase4_ColorTransition()
                delay(600)
                startPhase5_ScrollingRows()
                startPhase6_CtaReveal()
            }
        }
    }

    fun stop() {
        val animators = activeAnimators.toList()
        activeAnimators.clear()
        animators.forEach { it.cancel() }
        scrollAnimator?.cancel()
        scrollAnimator = null
        scrollTime = 0f
        circleTiles.clear()
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

        wordmark.imageTintList = ColorStateList.valueOf(navyColor)
        wordmark.alpha = 0f
        wordmark.scaleX = 0.95f
        wordmark.scaleY = 0.95f

        tagline.setTextColor(navyColor)
        tagline.alpha = 0f
        tagline.translationY = 15f

        acceptButton.alpha = 0f
        acceptButton.translationY = 20f
        termsText.alpha = 0f
        termsText.translationY = 10f

        emojiContainer.removeAllViews()
        circleTiles.clear()
        scrollingTiles.clear()
        scrollAnimator?.cancel()
        scrollAnimator = null
        scrollTime = 0f

        updateSystemBars(whiteColor, isLight = true)
        systemBarsFlipped = false
    }

    // === Phase 1: Logo Reveal (600ms) ===

    private suspend fun startPhase1_LogoReveal() = suspendCancellableCoroutine<Unit> { cont ->
        val animSet = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(wordmark, "alpha", 0f, 1f),
                ObjectAnimator.ofFloat(wordmark, "scaleX", 0.95f, 1f),
                ObjectAnimator.ofFloat(wordmark, "scaleY", 0.95f, 1f)
            )
            duration = 600
            interpolator = DecelerateInterpolator()
            addListener(onEnd { if (cont.isActive) cont.resume(Unit) })
        }
        cont.invokeOnCancellation { animSet.cancel() }
        trackAndStart(animSet)
    }

    // === Phase 2: Tagline (450ms) ===

    private suspend fun startPhase2_Tagline() = suspendCancellableCoroutine<Unit> { cont ->
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

    // === Phase 3: Circle Expansion (~1500ms) ===
    // Emojis appear from center, form a circle, hold, then burst outward

    private suspend fun startPhase3_CircleExpansion() {
        val centerX = emojiContainer.width / 2f
        val centerY = emojiContainer.height / 2f
        val density = activity.resources.displayMetrics.density
        val radius = min(emojiContainer.width, emojiContainer.height) * 0.30f

        circleTiles.clear()

        suspendCancellableCoroutine<Unit> { cont ->
            var completedCount = 0
            val phaseAnimators = mutableListOf<Animator>()
            circleTileData.forEachIndexed { index, tile ->
                val tileView = createCircleTileView(tile, density)
                emojiContainer.addView(tileView)
                circleTiles.add(tileView)

                val tileSize = when (tile.size) {
                    TileSize.SMALL -> 56 * density
                    TileSize.MEDIUM -> 72 * density
                    TileSize.LARGE -> 88 * density
                }

                val angle = index * (2.0 * Math.PI / circleTileData.size)
                val targetX = centerX + (radius * cos(angle)).toFloat() - tileSize / 2f
                val targetY = centerY + (radius * sin(angle)).toFloat() - tileSize / 2f
                val startX = centerX - tileSize / 2f
                val startY = centerY - tileSize / 2f

                tileView.translationX = startX
                tileView.translationY = startY
                tileView.scaleX = 0f
                tileView.scaleY = 0f
                tileView.alpha = 0f

                val targetRotation = -15f + (index * 4.3f)

                val animSet = AnimatorSet().apply {
                    playTogether(
                        ObjectAnimator.ofFloat(tileView, "alpha", 0f, 0.45f),
                        ObjectAnimator.ofFloat(tileView, "scaleX", 0f, 1f),
                        ObjectAnimator.ofFloat(tileView, "scaleY", 0f, 1f),
                        ObjectAnimator.ofFloat(tileView, "translationX", startX, targetX),
                        ObjectAnimator.ofFloat(tileView, "translationY", startY, targetY),
                        ObjectAnimator.ofFloat(tileView, "rotation", 0f, targetRotation)
                    )
                    duration = 500
                    startDelay = index * 60L
                    interpolator = OvershootInterpolator(0.6f)
                }

                animSet.addListener(onEnd {
                    completedCount++
                    if (completedCount == circleTileData.size) {
                        if (cont.isActive) cont.resume(Unit)
                    }
                })
                phaseAnimators.add(animSet)
                trackAndStart(animSet)
            }
            cont.invokeOnCancellation { phaseAnimators.forEach { it.cancel() } }
        }

        delay(250) // Hold the circle briefly

        startCircleBurst()
    }

    private suspend fun startCircleBurst() = suspendCancellableCoroutine<Unit> { cont ->
        val centerX = emojiContainer.width / 2f
        val centerY = emojiContainer.height / 2f
        var completedCount = 0
        val phaseAnimators = mutableListOf<Animator>()

        circleTiles.forEach { tileView ->
            val currentCenterX = tileView.translationX + tileView.width / 2f
            val currentCenterY = tileView.translationY + tileView.height / 2f

            val dx = currentCenterX - centerX
            val dy = currentCenterY - centerY
            val burstX = tileView.translationX + dx * 4f
            val burstY = tileView.translationY + dy * 4f

            val animSet = AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(tileView, "translationX", tileView.translationX, burstX),
                    ObjectAnimator.ofFloat(tileView, "translationY", tileView.translationY, burstY),
                    ObjectAnimator.ofFloat(tileView, "alpha", 0.45f, 0f),
                    ObjectAnimator.ofFloat(tileView, "scaleX", 1f, 0.6f),
                    ObjectAnimator.ofFloat(tileView, "scaleY", 1f, 0.6f)
                )
                duration = 550
                interpolator = AccelerateInterpolator(1.5f)
            }

            animSet.addListener(onEnd {
                completedCount++
                if (completedCount == circleTiles.size) {
                    emojiContainer.removeAllViews()
                    circleTiles.clear()
                    if (cont.isActive) cont.resume(Unit)
                }
            })
            phaseAnimators.add(animSet)
            trackAndStart(animSet)
        }
        cont.invokeOnCancellation { phaseAnimators.forEach { it.cancel() } }
    }

    // === Phase 4: Color Transition (1200ms) ===

    private suspend fun startPhase4_ColorTransition() = suspendCancellableCoroutine<Unit> { cont ->
        val bgView = container.findViewById<View>(R.id.welcome_background_overlay)
        val argbEvaluator = ArgbEvaluator()
        systemBarsFlipped = false

        val colorAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1200
            interpolator = AccelerateDecelerateInterpolator()

            addUpdateListener { animation ->
                val fraction = animation.animatedFraction

                val bgColor = argbEvaluator.evaluate(fraction, whiteColor, navyColor) as Int
                bgView?.setBackgroundColor(bgColor)

                val textColor = argbEvaluator.evaluate(fraction, navyColor, whiteColor) as Int
                wordmark.imageTintList = ColorStateList.valueOf(textColor)
                tagline.setTextColor(textColor)

                activity.window.statusBarColor = bgColor
                activity.window.navigationBarColor = bgColor

                if (fraction > 0.5f && !systemBarsFlipped) {
                    systemBarsFlipped = true
                    val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                    controller.isAppearanceLightStatusBars = false
                    controller.isAppearanceLightNavigationBars = false
                }
            }

            addListener(onEnd {
                updateSystemBars(navyColor, isLight = false)
                if (cont.isActive) cont.resume(Unit)
            })
        }
        cont.invokeOnCancellation { colorAnim.cancel() }
        trackAndStart(colorAnim)
    }

    // === Phase 5: Scrolling Rows Fade In (800ms) ===

    private suspend fun startPhase5_ScrollingRows() {
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
                val tileView = createRowTileView(item, density, tileSizePx)
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

    // === Phase 6: CTA Reveal (500ms) ===

    private suspend fun startPhase6_CtaReveal() = suspendCancellableCoroutine<Unit> { cont ->
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

    // === Tile Creation ===

    private fun createCircleTileView(tile: CircleTile, density: Float): View {
        val sizePx = when (tile.size) {
            TileSize.SMALL -> (56 * density).toInt()
            TileSize.MEDIUM -> (72 * density).toInt()
            TileSize.LARGE -> (88 * density).toInt()
        }
        val radiusPx = when (tile.size) {
            TileSize.SMALL -> 12 * density
            TileSize.MEDIUM -> 16 * density
            TileSize.LARGE -> 20 * density
        }
        val emojiSize = when (tile.size) {
            TileSize.SMALL -> 24f
            TileSize.MEDIUM -> 32f
            TileSize.LARGE -> 40f
        }

        val baseColor = ContextCompat.getColor(activity, tile.colorRes)
        val lighterColor = lightenColor(baseColor, 0.35f)

        return TextView(activity).apply {
            text = tile.emoji
            textSize = emojiSize
            gravity = Gravity.CENTER
            val bgDrawable = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(lighterColor, baseColor)
            )
            bgDrawable.cornerRadius = radiusPx
            background = bgDrawable
            layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
            elevation = 6 * density
        }
    }

    private fun createRowTileView(tile: RowTile, density: Float, sizePx: Int): View {
        return TextView(activity).apply {
            text = tile.emoji
            textSize = 22f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
        }
    }

    // === Helpers ===

    private fun lightenColor(color: Int, factor: Float): Int {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        return Color.argb(
            Color.alpha(color),
            (r + (255 - r) * factor).toInt(),
            (g + (255 - g) * factor).toInt(),
            (b + (255 - b) * factor).toInt()
        )
    }

    private fun updateSystemBars(bgColor: Int, isLight: Boolean) {
        activity.window.statusBarColor = bgColor
        activity.window.navigationBarColor = bgColor
        val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
        controller.isAppearanceLightStatusBars = isLight
        controller.isAppearanceLightNavigationBars = isLight
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
