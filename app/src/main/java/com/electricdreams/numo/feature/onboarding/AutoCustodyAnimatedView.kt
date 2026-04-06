package com.electricdreams.numo.feature.onboarding

import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
import com.electricdreams.numo.R
import com.electricdreams.numo.ui.util.isAnimationEnabled

/**
 * Animated stacked notification banners for "Automatic self-custody" slide.
 *
 * Each card cycles through: processing (spinner + "Processing payment")
 * → success (checkmark + amount sent) → exit. Back cards show completed state.
 */
class AutoCustodyAnimatedView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val allSubtitles = listOf(
        "21,000 sats sent to alice@getalby.com",
        "\$50.00 sent to satoshi@wallet.com",
        "100,000 sats sent to bob@strike.me",
        "\$75.00 sent to carol@coinos.io",
        "50,000 sats sent to dave@phoenix.acinq.co"
    )

    // Settled positions for 3-card stack
    private val settledY     = floatArrayOf(0f,  14f,  26f)
    private val settledScale = floatArrayOf(1f,  0.95f, 0.90f)
    private val settledAlpha = floatArrayOf(1f,  0.45f, 0.18f)

    // Core animation state
    private var introProgress = 0f
    private var backIntroProgress = 0f
    private var cycleProgress = 0f
    private var currentIndex = 0
    private var animStarted = false
    private var introComplete = false

    // Processing → Success
    private var successProgress = 0f  // 0 = processing (spinner), 1 = success (checkmark)

    private var introAnimator: AnimatorSet? = null
    private var cycleAnimator: ValueAnimator? = null
    private var successAnimator: ValueAnimator? = null
    private var cycleRunnable: Runnable? = null
    private var exitRunnable: Runnable? = null

    // Paints
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.color_card_shadow)
        maskFilter = BlurMaskFilter(14f, BlurMaskFilter.Blur.NORMAL)
    }
    private val iconBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val spinnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.color_bitcoin_orange)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.color_success_green)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.numo_navy)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
    private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.color_text_secondary)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    // Colors
    private val orangeBg = ContextCompat.getColor(context, R.color.color_auto_custody_pending_bg)
    private val greenBg = ContextCompat.getColor(context, R.color.color_auto_custody_success_bg)

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
            // Intro: back cards (success state), front card enters in processing state
            val bp = backIntroProgress
            if (bp > 0.01f) {
                drawBanner(canvas, cx, centerY + settledY[2] * s, bannerW, bannerH, bannerR, s,
                    subtitle(2), settledAlpha[2] * bp, settledScale[2])
                drawBanner(canvas, cx, centerY + settledY[1] * s, bannerW, bannerH, bannerR, s,
                    subtitle(1), settledAlpha[1] * bp, settledScale[1])
            }
            val p = introProgress
            drawBanner(canvas, cx, centerY + (settledY[0] + 50f * (1f - p)) * s,
                bannerW, bannerH, bannerR, s, subtitle(0), p, 0.95f + 0.05f * p,
                success = 0f, subtitleClip = p)

        } else if (cycleProgress > 0f) {
            // Cycle transition: all cards in success state
            val p = cycleProgress
            drawBanner(canvas, cx, centerY + lerp(28f, settledY[2], p) * s,
                bannerW, bannerH, bannerR, s, subtitle(3),
                lerp(0f, settledAlpha[2], p), lerp(0.85f, settledScale[2], p))
            drawBanner(canvas, cx, centerY + lerp(settledY[2], settledY[1], p) * s,
                bannerW, bannerH, bannerR, s, subtitle(2),
                lerp(settledAlpha[2], settledAlpha[1], p), lerp(settledScale[2], settledScale[1], p))
            // Card moving to front — already in processing state
            drawBanner(canvas, cx, centerY + lerp(settledY[1], settledY[0], p) * s,
                bannerW, bannerH, bannerR, s, subtitle(1),
                lerp(settledAlpha[1], settledAlpha[0], p), lerp(settledScale[1], settledScale[0], p),
                success = 0f)
            // Front card exits in success state
            drawBanner(canvas, cx, centerY + lerp(settledY[0], -40f, p) * s,
                bannerW, bannerH, bannerR, s, subtitle(0),
                lerp(settledAlpha[0], 0f, p), lerp(settledScale[0], 0.95f, p))

        } else {
            // Settled: back cards success, front card uses successProgress
            for (i in 2 downTo 0) {
                val success = if (i == 0) successProgress else 1f
                drawBanner(canvas, cx, centerY + settledY[i] * s, bannerW, bannerH, bannerR, s,
                    subtitle(i), settledAlpha[i], settledScale[i],
                    success = success)
            }
        }

        // Keep redrawing while spinner is visible
        if (animStarted && (!introComplete || (cycleProgress == 0f && successProgress < 1f))) {
            postInvalidateOnAnimation()
        }
    }

    private fun subtitle(offset: Int): String =
        allSubtitles[(currentIndex + offset) % allSubtitles.size]

    private fun drawBanner(
        canvas: Canvas, cx: Float, drawY: Float, w: Float, h: Float,
        r: Float, s: Float, subtitle: String, alpha: Float, scale: Float,
        success: Float = 1f, subtitleClip: Float = 1f
    ) {
        if (alpha < 0.01f) return
        canvas.save()
        canvas.translate(cx, drawY + h / 2f)
        canvas.scale(scale, scale)
        canvas.translate(-cx, -(drawY + h / 2f))

        val rect = RectF(cx - w / 2, drawY, cx + w / 2, drawY + h)

        // Shadow
        shadowPaint.alpha = (alpha * 50).toInt()
        canvas.drawRoundRect(
            RectF(rect.left + 2 * s, rect.top + 5 * s, rect.right + 2 * s, rect.bottom + 5 * s),
            r, r, shadowPaint
        )

        // Card background — back cards slightly darker for depth
        val gray = (240 + 15 * alpha).toInt().coerceIn(240, 255)
        cardPaint.color = Color.rgb(gray, gray, gray)
        cardPaint.alpha = (alpha * 255).toInt()
        canvas.drawRoundRect(rect, r, r, cardPaint)

        // Icon area
        val iconSize = 46f * s
        val iconR = 12f * s
        val iconX = rect.left + 16f * s
        val iconCy = drawY + h / 2f
        val iconY = iconCy - iconSize / 2f
        val bCx = iconX + iconSize / 2f

        // Icon background: orange (processing) → green (success)
        iconBgPaint.color = lerpColor(orangeBg, greenBg, success)
        iconBgPaint.alpha = (alpha * 255).toInt()
        canvas.drawRoundRect(
            RectF(iconX, iconY, iconX + iconSize, iconY + iconSize),
            iconR, iconR, iconBgPaint
        )

        // Spinner (fades out as success increases)
        if (success < 1f) {
            spinnerPaint.alpha = ((1f - success) * alpha * 255).toInt()
            spinnerPaint.strokeWidth = 2.5f * s
            val spinR = 10f * s
            val spinRect = RectF(bCx - spinR, iconCy - spinR, bCx + spinR, iconCy + spinR)
            val angle = ((System.nanoTime() / 5_000_000L) % 360).toFloat()
            canvas.drawArc(spinRect, angle, 270f, false, spinnerPaint)
        }

        // Checkmark (fades in as success increases)
        if (success > 0f) {
            checkPaint.alpha = (success * alpha * 255).toInt()
            checkPaint.strokeWidth = 2.5f * s
            val check = Path().apply {
                moveTo(bCx - 7f * s, iconCy)
                lineTo(bCx - 1.5f * s, iconCy + 6f * s)
                lineTo(bCx + 8f * s, iconCy - 6f * s)
            }
            canvas.drawPath(check, checkPaint)
        }

        // Title
        val textX = iconX + iconSize + 14f * s
        titlePaint.textSize = 15f * s
        titlePaint.alpha = (alpha * 255).toInt()
        canvas.drawText("Threshold Reached", textX, iconCy - 4f * s, titlePaint)

        // Subtitle: cross-fade "Processing payment" ↔ amount
        subtitlePaint.textSize = 12f * s
        val subY = iconCy + 16f * s

        if (success < 1f) {
            subtitlePaint.alpha = ((1f - success) * alpha * 200).toInt()
            val processingText = "Processing payment"
            if (subtitleClip < 0.99f) {
                val subW = subtitlePaint.measureText(processingText)
                canvas.save()
                canvas.clipRect(textX, drawY, textX + subW * subtitleClip, drawY + h)
                canvas.drawText(processingText, textX, subY, subtitlePaint)
                canvas.restore()
            } else {
                canvas.drawText(processingText, textX, subY, subtitlePaint)
            }
        }
        if (success > 0f) {
            subtitlePaint.alpha = (success * alpha * 200).toInt()
            canvas.drawText(subtitle, textX, subY, subtitlePaint)
        }

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
        introAnimator?.removeAllListeners()
        introAnimator?.cancel()
        introAnimator = null

        cycleAnimator?.removeAllListeners()
        cycleAnimator?.cancel()
        cycleAnimator = null

        successAnimator?.removeAllListeners()
        successAnimator?.cancel()
        successAnimator = null

        cycleRunnable?.let { removeCallbacks(it) }
        cycleRunnable = null
        exitRunnable?.let { removeCallbacks(it) }
        exitRunnable = null

        animStarted = false
        introComplete = false
        introProgress = 0f
        backIntroProgress = 0f
        cycleProgress = 0f
        successProgress = 0f
        currentIndex = 0
    }

    private fun startIntro() {
        if (animStarted) return
        animStarted = true

        if (!context.isAnimationEnabled()) {
            introProgress = 1f
            invalidate()
            return
        }

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
        cycleRunnable = Runnable { doSuccess() }
        postDelayed(cycleRunnable!!, 1500)
    }

    private fun doSuccess() {
        if (!isAttachedToWindow) return
        successAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 400
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                successProgress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (isAttachedToWindow) {
                        exitRunnable = Runnable { doCycle() }
                        postDelayed(exitRunnable!!, 1000)
                    }
                }
            })
            start()
        }
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
                        successProgress = 0f
                        invalidate()
                        scheduleCycle()
                    }
                }
            })
            start()
        }
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun lerpColor(from: Int, to: Int, t: Float): Int {
        val r = (Color.red(from) * (1 - t) + Color.red(to) * t).toInt()
        val g = (Color.green(from) * (1 - t) + Color.green(to) * t).toInt()
        val b = (Color.blue(from) * (1 - t) + Color.blue(to) * t).toInt()
        return Color.rgb(r, g, b)
    }
}
