package com.electricdreams.numo.feature.onboarding

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.electricdreams.numo.R
import com.google.android.material.button.MaterialButton

/**
 * Cinematic welcome screen animation with 6 sequenced phases:
 * 1. Logo reveal (navy on white, centered)
 * 2. Logo translates up + tagline appears (navy text on white)
 * 3. Emoji tiles float from center to lower positions (behind text, gradient fade)
 * 4. Emojis gently fade out
 * 5. Color transition (white bg → navy, navy text → white)
 * 6. Get Started button + terms fade in
 */
class OnboardingWelcomeAnimator(
    private val context: Context,
    private val container: FrameLayout,
    private val wordmark: ImageView,
    private val tagline: TextView,
    private val acceptButton: MaterialButton,
    private val termsText: TextView,
    private val emojiContainer: FrameLayout
) {
    private val navyColor = Color.parseColor("#0A2540")
    private val whiteColor = Color.WHITE

    // Smooth Material ease curve
    private val smoothEase = PathInterpolator(0.4f, 0f, 0.2f, 1f)

    private val tileData = listOf(
        TileItem("\uD83D\uDC55", R.color.chip_ribbon_cyan, Size.LARGE),
        TileItem("\uD83E\uDD69", R.color.chip_ribbon_pink, Size.MEDIUM),
        TileItem("\uD83C\uDF3F", R.color.chip_ribbon_lime, Size.SMALL),
        TileItem("\uD83E\uDD5C", R.color.chip_ribbon_green, Size.LARGE),
        TileItem("\uD83D\uDCB5", R.color.chip_ribbon_purple, Size.MEDIUM),
        TileItem("\uD83D\uDC2E", R.color.chip_ribbon_yellow, Size.SMALL),
        TileItem("\u2615", R.color.chip_ribbon_orange, Size.MEDIUM),
        TileItem("\uD83C\uDFB8", R.color.chip_ribbon_cyan, Size.SMALL)
    )

    // Target positions (x%, y%) — organic spread in the lower portion
    private val emojiTargetPositions = listOf(
        Pair(0.10f, 0.46f),
        Pair(0.80f, 0.43f),
        Pair(0.33f, 0.55f),
        Pair(0.64f, 0.52f),
        Pair(0.18f, 0.65f),
        Pair(0.76f, 0.62f),
        Pair(0.44f, 0.72f),
        Pair(0.56f, 0.80f)
    )

    private val activeAnimators = mutableListOf<Animator>()
    private val tiles = mutableListOf<View>()
    private var systemBarsFlipped = false

    private enum class Size { SMALL, MEDIUM, LARGE }

    private data class TileItem(
        val emoji: String,
        val colorRes: Int,
        val size: Size
    )

    fun start() {
        stop()
        resetAllViews()

        container.post {
            // Position wordmark at vertical center via translationY offset
            val containerCenterY = container.height / 2f
            val wordmarkCenterY = wordmark.y + wordmark.height / 2f
            val offsetToCenter = containerCenterY - wordmarkCenterY
            wordmark.translationY = offsetToCenter

            startPhase1_LogoReveal {
                startPhase2_LogoMoveAndTagline {
                    container.postDelayed({
                        startPhase3_EmojiFloat {
                            startPhase4_EmojisFadeOut {
                                startPhase5_ColorTransition {
                                    startPhase6_CtaReveal()
                                }
                            }
                        }
                    }, 200)
                }
            }
        }
    }

    fun stop() {
        activeAnimators.forEach { it.cancel() }
        activeAnimators.clear()
        tiles.clear()
        emojiContainer.removeAllViews()
        systemBarsFlipped = false
    }

    private fun resetAllViews() {
        container.findViewById<View>(R.id.welcome_background_overlay)
            ?.setBackgroundColor(whiteColor)

        wordmark.imageTintList = ColorStateList.valueOf(navyColor)
        wordmark.alpha = 0f
        wordmark.translationY = 0f
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
        tiles.clear()

        updateSystemBars(whiteColor, isLight = true)
        systemBarsFlipped = false
    }

    // === Phase 1: Logo Reveal (600ms) ===

    private fun startPhase1_LogoReveal(onComplete: () -> Unit) {
        val animSet = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(wordmark, "alpha", 0f, 1f),
                ObjectAnimator.ofFloat(wordmark, "scaleX", 0.95f, 1f),
                ObjectAnimator.ofFloat(wordmark, "scaleY", 0.95f, 1f)
            )
            duration = 600
            interpolator = DecelerateInterpolator()
            addListener(onEnd { onComplete() })
        }
        trackAndStart(animSet)
    }

    // === Phase 2: Logo Move + Tagline (750ms) ===

    private fun startPhase2_LogoMoveAndTagline(onComplete: () -> Unit) {
        val currentY = wordmark.translationY

        val logoMove = ObjectAnimator.ofFloat(wordmark, "translationY", currentY, 0f).apply {
            duration = 700
            interpolator = DecelerateInterpolator(1.5f)
        }

        val tagAlpha = ObjectAnimator.ofFloat(tagline, "alpha", 0f, 1f).apply {
            duration = 450
            startDelay = 300
            interpolator = DecelerateInterpolator()
        }
        val tagTranslate = ObjectAnimator.ofFloat(tagline, "translationY", 15f, 0f).apply {
            duration = 450
            startDelay = 300
            interpolator = DecelerateInterpolator()
        }

        val animSet = AnimatorSet().apply {
            playTogether(logoMove, tagAlpha, tagTranslate)
            addListener(onEnd { onComplete() })
        }
        trackAndStart(animSet)
    }

    // === Phase 3: Emoji Float ===
    // Tiles materialize near center and drift gently to lower positions

    private fun startPhase3_EmojiFloat(onComplete: () -> Unit) {
        val centerX = emojiContainer.width / 2f
        val centerY = emojiContainer.height / 2f
        val density = context.resources.displayMetrics.density
        var completedCount = 0

        tiles.clear()

        tileData.forEachIndexed { index, tile ->
            val tileView = createTileView(tile, density)
            emojiContainer.addView(tileView)
            tiles.add(tileView)

            val tileSize = when (tile.size) {
                Size.SMALL -> 56 * density
                Size.MEDIUM -> 72 * density
                Size.LARGE -> 88 * density
            }

            // Slightly staggered start positions to avoid mechanical center burst
            val offsetX = (-1f + (index % 4) * 0.6f) * 20f * density / 3f
            val offsetY = (-1f + (index % 3) * 0.8f) * 15f * density / 3f
            val startX = centerX - tileSize / 2f + offsetX
            val startY = centerY - tileSize / 2f + offsetY

            val (xFrac, yFrac) = emojiTargetPositions[index]
            val targetX = emojiContainer.width * xFrac - tileSize / 2f
            val targetY = emojiContainer.height * yFrac - tileSize / 2f

            // Start partially scaled so they "grow in" rather than pop from nothing
            tileView.translationX = startX
            tileView.translationY = startY
            tileView.scaleX = 0.4f
            tileView.scaleY = 0.4f
            tileView.alpha = 0f

            val targetRotation = -5f + (index * 1.8f)

            val animSet = AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(tileView, "alpha", 0f, 0.85f),
                    ObjectAnimator.ofFloat(tileView, "scaleX", 0.4f, 1f),
                    ObjectAnimator.ofFloat(tileView, "scaleY", 0.4f, 1f),
                    ObjectAnimator.ofFloat(tileView, "translationX", startX, targetX),
                    ObjectAnimator.ofFloat(tileView, "translationY", startY, targetY),
                    ObjectAnimator.ofFloat(tileView, "rotation", 0f, targetRotation)
                )
                duration = 1000
                startDelay = index * 100L
                interpolator = smoothEase
            }

            animSet.addListener(onEnd {
                completedCount++
                if (completedCount == tileData.size) {
                    container.postDelayed({ onComplete() }, 500)
                }
            })
            trackAndStart(animSet)
        }

        // Edge gradients on top of tiles for smooth fade at edges
        addEdgeGradients()
    }

    private fun addEdgeGradients() {
        // Bottom gradient (stronger — tiles concentrate here)
        val bottomGradient = View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, whiteColor)
            )
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                (emojiContainer.height * 0.35f).toInt()
            ).apply {
                gravity = Gravity.BOTTOM
            }
        }
        emojiContainer.addView(bottomGradient)

        // Top gradient (subtle — just softens the top edge)
        val topGradient = View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.TRANSPARENT, whiteColor)
            )
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                (emojiContainer.height * 0.25f).toInt()
            ).apply {
                gravity = Gravity.TOP
            }
        }
        emojiContainer.addView(topGradient)
    }

    // === Phase 4: Emojis Fade Out (700ms) ===

    private fun startPhase4_EmojisFadeOut(onComplete: () -> Unit) {
        val childCount = emojiContainer.childCount
        if (childCount == 0) {
            onComplete()
            return
        }

        var completedCount = 0

        for (i in 0 until childCount) {
            val child = emojiContainer.getChildAt(i)

            val animSet = AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(child, "alpha", child.alpha, 0f),
                    ObjectAnimator.ofFloat(child, "scaleX", child.scaleX, 0.9f),
                    ObjectAnimator.ofFloat(child, "scaleY", child.scaleY, 0.9f)
                )
                duration = 700
                interpolator = smoothEase
            }

            animSet.addListener(onEnd {
                completedCount++
                if (completedCount == childCount) {
                    emojiContainer.removeAllViews()
                    tiles.clear()
                    onComplete()
                }
            })
            trackAndStart(animSet)
        }
    }

    // === Phase 5: Color Transition (1200ms) ===
    // Smooth cross-fade from white to navy

    private fun startPhase5_ColorTransition(onComplete: () -> Unit) {
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

                (context as? Activity)?.let { activity ->
                    activity.window.statusBarColor = bgColor
                    activity.window.navigationBarColor = bgColor
                }

                // Flip icon appearance at the midpoint
                if (fraction > 0.5f && !systemBarsFlipped) {
                    systemBarsFlipped = true
                    (context as? Activity)?.let { activity ->
                        val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                        controller.isAppearanceLightStatusBars = false
                        controller.isAppearanceLightNavigationBars = false
                    }
                }
            }

            addListener(onEnd {
                updateSystemBars(navyColor, isLight = false)
                onComplete()
            })
        }
        trackAndStart(colorAnim)
    }

    // === Phase 6: CTA Reveal (600ms) ===

    private fun startPhase6_CtaReveal() {
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
        }
        trackAndStart(animSet)
    }

    // === Tile Creation ===

    private fun createTileView(tile: TileItem, density: Float): View {
        val sizePx = when (tile.size) {
            Size.SMALL -> (56 * density).toInt()
            Size.MEDIUM -> (72 * density).toInt()
            Size.LARGE -> (88 * density).toInt()
        }

        val radiusPx = when (tile.size) {
            Size.SMALL -> 12 * density
            Size.MEDIUM -> 16 * density
            Size.LARGE -> 20 * density
        }

        val emojiSize = when (tile.size) {
            Size.SMALL -> 24f
            Size.MEDIUM -> 32f
            Size.LARGE -> 40f
        }

        val baseColor = ContextCompat.getColor(context, tile.colorRes)
        val lighterColor = lightenColor(baseColor, 0.35f)

        return TextView(context).apply {
            text = tile.emoji
            textSize = emojiSize
            gravity = Gravity.CENTER

            // Subtle diagonal gradient fill for depth
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
        (context as? Activity)?.let { activity ->
            activity.window.statusBarColor = bgColor
            activity.window.navigationBarColor = bgColor
            val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
            controller.isAppearanceLightStatusBars = isLight
            controller.isAppearanceLightNavigationBars = isLight
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
