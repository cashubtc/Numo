package com.electricdreams.numo.feature.onboarding

import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

/**
 * Animated stacked notification banners for "Automatic self-custody" slide.
 *
 * Intro: Front banner slides in, back cards fade in behind.
 * Cycle: Front banner slides up and fades out, stack shifts forward,
 *        new card appears at back. Repeats every ~2.5s.
 *
 * All positions are derived from two progress floats — no mutable banner list.
 */
class AutoCustodyAnimatedView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

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

    // Animation state — these floats drive ALL rendering
    private var introProgress = 0f
    private var backIntroProgress = 0f
    private var cycleProgress = 0f
    private var currentIndex = 0
    private var animStarted = false
    private var introComplete = false

    private var introAnimator: AnimatorSet? = null
    private var cycleAnimator: ValueAnimator? = null
    private var cycleRunnable: Runnable? = null

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

        if (!introComplete) {
            // Intro: back cards first, then front on top
            val bp = backIntroProgress
            if (bp > 0.01f) {
                drawBanner(canvas, cx, centerY + settledY[2] * s, bannerW, bannerH, bannerR, s,
                    subtitle(2), settledAlpha[2] * bp, settledScale[2])
                drawBanner(canvas, cx, centerY + settledY[1] * s, bannerW, bannerH, bannerR, s,
                    subtitle(1), settledAlpha[1] * bp, settledScale[1])
            }
            val p = introProgress
            drawBanner(canvas, cx, centerY + (settledY[0] + 50f * (1f - p)) * s,
                bannerW, bannerH, bannerR, s, subtitle(0), p, 0.95f + 0.05f * p)

        } else if (cycleProgress > 0f) {
            // Cycle transition: 4 cards derived from cycleProgress, back to front
            val p = cycleProgress
            drawBanner(canvas, cx, centerY + lerp(28f, settledY[2], p) * s,
                bannerW, bannerH, bannerR, s, subtitle(3),
                lerp(0f, settledAlpha[2], p), lerp(0.85f, settledScale[2], p))
            drawBanner(canvas, cx, centerY + lerp(settledY[2], settledY[1], p) * s,
                bannerW, bannerH, bannerR, s, subtitle(2),
                lerp(settledAlpha[2], settledAlpha[1], p), lerp(settledScale[2], settledScale[1], p))
            drawBanner(canvas, cx, centerY + lerp(settledY[1], settledY[0], p) * s,
                bannerW, bannerH, bannerR, s, subtitle(1),
                lerp(settledAlpha[1], settledAlpha[0], p), lerp(settledScale[1], settledScale[0], p))
            drawBanner(canvas, cx, centerY + lerp(settledY[0], -40f, p) * s,
                bannerW, bannerH, bannerR, s, subtitle(0),
                lerp(settledAlpha[0], 0f, p), lerp(settledScale[0], 0.95f, p))

        } else {
            // Settled: 3 banners at rest, back to front
            for (i in 2 downTo 0) {
                drawBanner(canvas, cx, centerY + settledY[i] * s, bannerW, bannerH, bannerR, s,
                    subtitle(i), settledAlpha[i], settledScale[i])
            }
        }
    }

    private fun subtitle(offset: Int): String =
        allSubtitles[(currentIndex + offset) % allSubtitles.size]

    private fun drawBanner(
        canvas: Canvas, cx: Float, drawY: Float, w: Float, h: Float,
        r: Float, s: Float, subtitle: String, alpha: Float, scale: Float
    ) {
        if (alpha < 0.01f) return
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

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (width > 0 && height > 0 && !animStarted) {
            startIntro()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0 && !animStarted) {
            startIntro()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAll()
    }

    private fun stopAll() {
        // Remove listeners BEFORE cancel to prevent onAnimationEnd cascades
        introAnimator?.removeAllListeners()
        introAnimator?.cancel()
        introAnimator = null

        cycleAnimator?.removeAllListeners()
        cycleAnimator?.cancel()
        cycleAnimator = null

        cycleRunnable?.let { removeCallbacks(it) }
        cycleRunnable = null

        // Reset so animation replays fresh on re-attach
        animStarted = false
        introComplete = false
        introProgress = 0f
        backIntroProgress = 0f
        cycleProgress = 0f
        currentIndex = 0
    }

    private fun startIntro() {
        if (animStarted) return
        animStarted = true

        val frontIn = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 600
            startDelay = 300
            interpolator = OvershootInterpolator(0.8f)
            addUpdateListener {
                introProgress = it.animatedValue as Float
                invalidate()
            }
        }

        val backIn = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 500
            startDelay = 1000
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener {
                backIntroProgress = it.animatedValue as Float
                invalidate()
            }
        }

        introAnimator = AnimatorSet().apply {
            playTogether(frontIn, backIn)
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    introComplete = true
                    invalidate()
                    scheduleCycle()
                }
            })
            start()
        }
    }

    private fun scheduleCycle() {
        if (!isAttachedToWindow) return
        cycleRunnable = Runnable { doCycle() }
        postDelayed(cycleRunnable!!, 2500)
    }

    private fun doCycle() {
        if (!isAttachedToWindow) return

        cycleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 500
            interpolator = DecelerateInterpolator(1.8f)
            addUpdateListener {
                cycleProgress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (isAttachedToWindow) {
                        currentIndex = (currentIndex + 1) % allSubtitles.size
                        cycleProgress = 0f
                        invalidate()
                        scheduleCycle()
                    }
                }
            })
            start()
        }
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}
