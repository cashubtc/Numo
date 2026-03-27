package com.electricdreams.numo.feature.onboarding

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Stacked notification banners for "Automatic self-custody" slide.
 * The stack is static (front card + 2 decorative cards behind).
 * The front card's content crossfades between different withdrawal entries,
 * with a subtle slide-up on each transition — clean and jank-free.
 */
class AutoCustodyAnimatedView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val subtitles = listOf(
        "\$100.00 sent to alice@getalby.com",
        "\$50.00 sent to satoshi@wallet.com",
        "\$25.00 sent to bob@strike.me"
    )

    private var currentIndex = 0
    private var transitionProgress = 0f  // 0 = showing current, 0→1 = crossfading to next
    private var cycleAnimator: ValueAnimator? = null
    private var cycleRunnable: Runnable? = null

    // Stack visual config
    private val stackScales = floatArrayOf(1.00f, 0.95f, 0.90f)
    private val stackOffsets = floatArrayOf(0f, 10f, 18f)
    private val stackAlphas = floatArrayOf(1.00f, 0.45f, 0.18f)

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
        val cx = w / 2f
        val s = w / 380f
        val bannerW = 340f * s
        val bannerH = 78f * s
        val bannerR = 14f * s
        val centerY = h * 0.4f

        // Draw back decorative cards (static, no content)
        for (i in 2 downTo 1) {
            val drawY = centerY + stackOffsets[i] * s
            val alpha = stackAlphas[i]
            val scale = stackScales[i]

            canvas.save()
            canvas.translate(cx, drawY + bannerH / 2f)
            canvas.scale(scale, scale)
            canvas.translate(-cx, -(drawY + bannerH / 2f))

            val rect = RectF(cx - bannerW / 2, drawY, cx + bannerW / 2, drawY + bannerH)

            shadowPaint.alpha = (alpha * 50).toInt()
            canvas.drawRoundRect(
                RectF(rect.left + 2 * s, rect.top + 5 * s, rect.right + 2 * s, rect.bottom + 5 * s),
                bannerR, bannerR, shadowPaint
            )
            cardPaint.alpha = (alpha * 255).toInt()
            canvas.drawRoundRect(rect, bannerR, bannerR, cardPaint)

            canvas.restore()
        }

        // Draw front card with crossfading content
        val drawY = centerY + stackOffsets[0] * s
        val rect = RectF(cx - bannerW / 2, drawY, cx + bannerW / 2, drawY + bannerH)

        // Shadow
        shadowPaint.alpha = 50
        canvas.drawRoundRect(
            RectF(rect.left + 2 * s, rect.top + 5 * s, rect.right + 2 * s, rect.bottom + 5 * s),
            bannerR, bannerR, shadowPaint
        )
        // Card
        cardPaint.alpha = 255
        canvas.drawRoundRect(rect, bannerR, bannerR, cardPaint)

        // Lightning icon (always visible)
        val iconSize = 46f * s
        val iconR = 12f * s
        val iconX = rect.left + 16f * s
        val iconCy = drawY + bannerH / 2f
        val iconY = iconCy - iconSize / 2f

        orangeBgPaint.alpha = 255
        canvas.drawRoundRect(
            RectF(iconX, iconY, iconX + iconSize, iconY + iconSize),
            iconR, iconR, orangeBgPaint
        )
        lightningPaint.alpha = 255
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

        // Title (always visible, doesn't change)
        val textX = iconX + iconSize + 14f * s
        titlePaint.textSize = 15f * s
        titlePaint.alpha = 255
        canvas.drawText("Threshold Reached", textX, iconCy - 4f * s, titlePaint)

        // Subtitle — crossfade between current and next
        subtitlePaint.textSize = 12f * s
        val p = transitionProgress
        val nextIndex = (currentIndex + 1) % subtitles.size

        if (p < 0.01f) {
            // Static — show current
            subtitlePaint.alpha = 200
            canvas.drawText(subtitles[currentIndex], textX, iconCy + 16f * s, subtitlePaint)
        } else {
            // Outgoing: fade out + slide up slightly
            val outAlpha = ((1f - p) * 200).toInt()
            val outSlide = p * -8f * s
            subtitlePaint.alpha = outAlpha
            canvas.drawText(subtitles[currentIndex], textX, iconCy + 16f * s + outSlide, subtitlePaint)

            // Incoming: fade in + slide up from below
            val inAlpha = (p * 200).toInt()
            val inSlide = (1f - p) * 8f * s
            subtitlePaint.alpha = inAlpha
            canvas.drawText(subtitles[nextIndex], textX, iconCy + 16f * s + inSlide, subtitlePaint)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startCycling()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopCycling()
    }

    private fun startCycling() {
        stopCycling()
        currentIndex = 0
        transitionProgress = 0f
        invalidate()
        scheduleCycle()
    }

    private fun stopCycling() {
        cycleAnimator?.cancel()
        cycleAnimator = null
        cycleRunnable?.let { removeCallbacks(it) }
        cycleRunnable = null
    }

    private fun scheduleCycle() {
        cycleRunnable = Runnable { doCycle() }
        postDelayed(cycleRunnable!!, 2500)
    }

    private fun doCycle() {
        cycleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 400
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener {
                transitionProgress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    currentIndex = (currentIndex + 1) % subtitles.size
                    transitionProgress = 0f
                    invalidate()
                    scheduleCycle()
                }
            })
            start()
        }
    }
}
