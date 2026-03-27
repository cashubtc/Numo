package com.electricdreams.numo.feature.onboarding

import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

/**
 * Animated stacked notification banners for "Automatic self-custody" slide.
 *
 * Intro: Front banner slides in → back cards appear behind.
 * Cycle: Front banner slides UP and fades out (dismissed), back cards
 *        slide forward to fill the gap, a new card appears at the back.
 */
class AutoCustodyAnimatedView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private data class BannerState(
        val subtitle: String,
        var y: Float,        // Y offset from center (in dp-like units)
        var scale: Float,
        var alpha: Float
    )

    private val allSubtitles = listOf(
        "\$100.00 sent to alice@getalby.com",
        "\$50.00 sent to satoshi@wallet.com",
        "\$25.00 sent to bob@strike.me",
        "\$75.00 sent to carol@coinos.io",
        "\$30.00 sent to dave@phoenix.acinq.co"
    )

    // Settled positions for 3-card stack
    private val settledY     = floatArrayOf(0f,  10f,  18f)
    private val settledScale = floatArrayOf(1f,  0.95f, 0.90f)
    private val settledAlpha = floatArrayOf(1f,  0.45f, 0.18f)

    private var banners = mutableListOf<BannerState>()
    private var nextSubtitleIndex = 3 // next one to pull from allSubtitles

    private var introAnimator: AnimatorSet? = null
    private var cycleAnimator: AnimatorSet? = null
    private var cycleRunnable: Runnable? = null
    private var introComplete = false

    // Paints
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.FILL
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#20000000")
        maskFilter = BlurMaskFilter(14f, BlurMaskFilter.Blur.NORMAL)
    }
    private val orangeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFF0DB"); style = Paint.Style.FILL
    }
    private val lightningPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F7931A"); style = Paint.Style.FILL
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0A2540")
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
    private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6F6F73")
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return
        val cx = w / 2f
        val s = w / 380f
        val bannerW = 340f * s
        val bannerH = 78f * s
        val bannerR = 14f * s
        val centerY = h * 0.4f

        // Draw back to front
        for (i in (banners.size - 1) downTo 0) {
            val b = banners[i]
            if (b.alpha < 0.01f) continue
            drawBanner(canvas, cx, centerY + b.y * s, bannerW, bannerH, bannerR, s, b.subtitle, b.alpha, b.scale)
        }
    }

    private fun drawBanner(
        canvas: Canvas, cx: Float, drawY: Float, w: Float, h: Float,
        r: Float, s: Float, subtitle: String, alpha: Float, scale: Float
    ) {
        canvas.save()
        canvas.translate(cx, drawY + h / 2f)
        canvas.scale(scale, scale)
        canvas.translate(-cx, -(drawY + h / 2f))

        val rect = RectF(cx - w / 2, drawY, cx + w / 2, drawY + h)

        shadowPaint.alpha = (alpha * 50).toInt()
        canvas.drawRoundRect(
            RectF(rect.left + 2 * s, rect.top + 5 * s, rect.right + 2 * s, rect.bottom + 5 * s),
            r, r, shadowPaint
        )
        cardPaint.alpha = (alpha * 255).toInt()
        canvas.drawRoundRect(rect, r, r, cardPaint)

        val iconSize = 46f * s
        val iconR = 12f * s
        val iconX = rect.left + 16f * s
        val iconCy = drawY + h / 2f
        val iconY = iconCy - iconSize / 2f

        orangeBgPaint.alpha = (alpha * 255).toInt()
        canvas.drawRoundRect(
            RectF(iconX, iconY, iconX + iconSize, iconY + iconSize),
            iconR, iconR, orangeBgPaint
        )
        lightningPaint.alpha = (alpha * 255).toInt()
        val bCx = iconX + iconSize / 2f
        val bolt = Path().apply {
            moveTo(bCx + 1.5f * s, iconCy - 11f * s)
            lineTo(bCx - 6f * s, iconCy + 1f * s)
            lineTo(bCx - 0.5f * s, iconCy + 1f * s)
            lineTo(bCx - 1.5f * s, iconCy + 11f * s)
            lineTo(bCx + 6f * s, iconCy - 1f * s)
            lineTo(bCx + 0.5f * s, iconCy - 1f * s)
            close()
        }
        canvas.drawPath(bolt, lightningPaint)

        val textX = iconX + iconSize + 14f * s
        titlePaint.textSize = 15f * s
        titlePaint.alpha = (alpha * 255).toInt()
        canvas.drawText("Threshold Reached", textX, iconCy - 4f * s, titlePaint)

        subtitlePaint.textSize = 12f * s
        subtitlePaint.alpha = (alpha * 200).toInt()
        canvas.drawText(subtitle, textX, iconCy + 16f * s, subtitlePaint)

        canvas.restore()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0 && !introComplete) {
            startIntro()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAll()
    }

    private fun stopAll() {
        introAnimator?.cancel(); introAnimator = null
        cycleAnimator?.cancel(); cycleAnimator = null
        cycleRunnable?.let { removeCallbacks(it) }; cycleRunnable = null
    }

    private fun startIntro() {
        stopAll()
        introComplete = false
        nextSubtitleIndex = 3

        // Create initial 3 banners
        banners.clear()
        banners.add(BannerState(allSubtitles[0], settledY[0], settledScale[0], 0f)) // front
        banners.add(BannerState(allSubtitles[1], settledY[1], settledScale[1], 0f)) // middle
        banners.add(BannerState(allSubtitles[2], settledY[2], settledScale[2], 0f)) // back

        // Phase 1: Front banner slides in
        val frontIn = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 600; startDelay = 300
            interpolator = OvershootInterpolator(0.8f)
            addUpdateListener {
                val p = it.animatedValue as Float
                banners[0].alpha = p
                banners[0].y = settledY[0] + 50f * (1f - p)
                banners[0].scale = 0.95f + 0.05f * p
                invalidate()
            }
        }

        // Phase 2: Back cards fade in
        val backIn = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 500; startDelay = 1000
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener {
                val p = it.animatedValue as Float
                banners[1].alpha = settledAlpha[1] * p
                banners[2].alpha = settledAlpha[2] * p
                invalidate()
            }
        }

        introAnimator = AnimatorSet().apply {
            playTogether(frontIn, backIn)
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    introComplete = true
                    scheduleCycle()
                }
            })
            start()
        }
    }

    private fun scheduleCycle() {
        cycleRunnable = Runnable { doCycle() }
        postDelayed(cycleRunnable!!, 2500)
    }

    private fun doCycle() {
        if (banners.size < 3) return

        // Snapshot the front banner's current values for animation
        val front = banners[0]
        val mid = banners[1]
        val back = banners[2]

        val frontStartY = front.y
        val frontStartAlpha = front.alpha
        val midStartY = mid.y
        val midStartScale = mid.scale
        val midStartAlpha = mid.alpha
        val backStartY = back.y
        val backStartScale = back.scale
        val backStartAlpha = back.alpha

        // Prepare the new back card (starts invisible)
        val newSubtitle = allSubtitles[nextSubtitleIndex % allSubtitles.size]
        nextSubtitleIndex++
        val newCard = BannerState(newSubtitle, settledY[2] + 10f, settledScale[2], 0f)
        banners.add(newCard)

        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 500
            interpolator = DecelerateInterpolator(1.8f)
            addUpdateListener {
                val p = it.animatedValue as Float

                // Front: slides up and fades out
                front.y = frontStartY - 40f * p
                front.alpha = frontStartAlpha * (1f - p)
                front.scale = 1f - 0.05f * p

                // Middle → front position
                mid.y = lerp(midStartY, settledY[0], p)
                mid.scale = lerp(midStartScale, settledScale[0], p)
                mid.alpha = lerp(midStartAlpha, settledAlpha[0], p)

                // Back → middle position
                back.y = lerp(backStartY, settledY[1], p)
                back.scale = lerp(backStartScale, settledScale[1], p)
                back.alpha = lerp(backStartAlpha, settledAlpha[1], p)

                // New card → back position
                newCard.y = lerp(settledY[2] + 10f, settledY[2], p)
                newCard.alpha = settledAlpha[2] * p
                newCard.scale = settledScale[2]

                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    // Remove the dismissed front card
                    banners.remove(front)
                    invalidate()
                    scheduleCycle()
                }
            })
            start()
        }
        cycleAnimator = AnimatorSet().apply { play(anim); start() }
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}
