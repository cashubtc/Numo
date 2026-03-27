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
 * Animated illustration for "Automatic self-custody" slide.
 * Shows notification banners sliding in one after another with different
 * amounts and lightning addresses, creating a "payments flowing to your wallet" effect.
 */
class AutoCustodyAnimatedView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private data class Banner(
        val title: String,
        val subtitle: String,
        var offsetY: Float = 0f,    // current vertical offset (0 = final position)
        var alpha: Float = 0f,      // current opacity
        var scale: Float = 0.95f    // current scale
    )

    private val banners = listOf(
        Banner("Threshold Reached", "\$100.00 sent to alice@getalby.com"),
        Banner("Withdrawal Sent", "\$50.00 sent to satoshi@wallet.com"),
        Banner("Threshold Reached", "\$25.00 sent to bob@strike.me")
    )

    private var animStarted = false
    private var animatorSet: AnimatorSet? = null

    // Paints
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#18000000")
        maskFilter = BlurMaskFilter(16f, BlurMaskFilter.Blur.NORMAL)
    }
    private val orangeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFF0DB")
        style = Paint.Style.FILL
    }
    private val greenBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5EFFC2")
        style = Paint.Style.FILL
    }
    private val lightningPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F7931A")
        style = Paint.Style.FILL
    }
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0A2540")
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
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
        val startY = h * 0.25f
        val gap = bannerH + 14f * s

        // Draw banners back to front (last banner drawn on top)
        for (i in banners.indices.reversed()) {
            val banner = banners[i]
            if (banner.alpha < 0.01f) continue

            val baseY = startY + i * gap
            val drawY = baseY + banner.offsetY * s

            canvas.save()
            canvas.translate(cx, drawY + bannerH / 2f)
            canvas.scale(banner.scale, banner.scale)
            canvas.translate(-cx, -(drawY + bannerH / 2f))

            val rect = RectF(cx - bannerW / 2, drawY, cx + bannerW / 2, drawY + bannerH)

            // Shadow
            shadowPaint.alpha = (banner.alpha * 40).toInt()
            canvas.drawRoundRect(
                RectF(rect.left + 2 * s, rect.top + 6 * s, rect.right + 2 * s, rect.bottom + 6 * s),
                bannerR, bannerR, shadowPaint
            )

            // Card
            cardPaint.alpha = (banner.alpha * 255).toInt()
            canvas.drawRoundRect(rect, bannerR, bannerR, cardPaint)

            // Icon
            val iconSize = 46f * s
            val iconR = 12f * s
            val iconX = rect.left + 16f * s
            val iconCy = drawY + bannerH / 2f
            val iconY = iconCy - iconSize / 2f

            if (i == 0 || i == 2) {
                // Lightning bolt icon
                orangeBgPaint.alpha = (banner.alpha * 255).toInt()
                canvas.drawRoundRect(
                    RectF(iconX, iconY, iconX + iconSize, iconY + iconSize),
                    iconR, iconR, orangeBgPaint
                )
                lightningPaint.alpha = (banner.alpha * 255).toInt()
                val bCx = iconX + iconSize / 2f
                val bCy = iconCy
                val bolt = Path().apply {
                    moveTo(bCx + 1.5f * s, bCy - 11f * s)
                    lineTo(bCx - 6f * s, bCy + 1f * s)
                    lineTo(bCx - 0.5f * s, bCy + 1f * s)
                    lineTo(bCx - 1.5f * s, bCy + 11f * s)
                    lineTo(bCx + 6f * s, bCy - 1f * s)
                    lineTo(bCx + 0.5f * s, bCy - 1f * s)
                    close()
                }
                canvas.drawPath(bolt, lightningPaint)
            } else {
                // Green checkmark icon
                greenBgPaint.alpha = (banner.alpha * 255).toInt()
                canvas.drawRoundRect(
                    RectF(iconX, iconY, iconX + iconSize, iconY + iconSize),
                    iconR, iconR, greenBgPaint
                )
                checkPaint.alpha = (banner.alpha * 255).toInt()
                checkPaint.strokeWidth = 2.8f * s
                val cCx = iconX + iconSize / 2f
                val checkPath = Path().apply {
                    moveTo(cCx - 8f * s, iconCy + 1f * s)
                    lineTo(cCx - 2f * s, iconCy + 7f * s)
                    lineTo(cCx + 9f * s, iconCy - 5f * s)
                }
                canvas.drawPath(checkPath, checkPaint)
            }

            // Text
            val textX = iconX + iconSize + 14f * s
            titlePaint.textSize = 15f * s
            titlePaint.alpha = (banner.alpha * 255).toInt()
            canvas.drawText(banner.title, textX, iconCy - 4f * s, titlePaint)

            subtitlePaint.textSize = 12f * s
            subtitlePaint.alpha = (banner.alpha * 200).toInt()
            canvas.drawText(banner.subtitle, textX, iconCy + 16f * s, subtitlePaint)

            canvas.restore()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!animStarted) {
            postDelayed({ startAnimation() }, 400)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animatorSet?.cancel()
    }

    private fun startAnimation() {
        if (animStarted) return
        animStarted = true

        val animators = mutableListOf<android.animation.Animator>()

        banners.forEachIndexed { index, banner ->
            banner.offsetY = 60f
            banner.alpha = 0f
            banner.scale = 0.95f

            // Slide up + fade in
            val anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 600
                startDelay = index * 350L + 200L
                interpolator = OvershootInterpolator(0.8f)
                addUpdateListener {
                    val p = it.animatedValue as Float
                    banner.offsetY = 60f * (1f - p)
                    banner.alpha = p
                    banner.scale = 0.95f + 0.05f * p
                    invalidate()
                }
            }
            animators.add(anim)
        }

        animatorSet = AnimatorSet().apply {
            playTogether(animators)
            start()
        }
    }
}
