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
 * Animated "Zero Fees" illustration.
 * Phase 1: Fee percentages fade in scattered around the screen.
 * Phase 2: Each fee gets individually slashed — a line cuts through it,
 *          then it splits apart and fades away, one after another.
 * Phase 3: Large "0%" bounces in.
 */
class ZeroFeesIllustration @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private data class FeeLabel(
        val text: String,
        val baseX: Float,   // 0..1 relative
        val baseY: Float,
        val size: Float,    // text size multiplier
        var alpha: Float = 0f,
        var slashProgress: Float = 0f,  // 0..1 line draw
        var destroyed: Float = 0f       // 0..1 split + fade
    )

    private val fees = listOf(
        FeeLabel("3%",   0.22f, 0.28f, 1.1f),
        FeeLabel("2.5%", 0.70f, 0.24f, 0.85f),
        FeeLabel("1.5%", 0.18f, 0.58f, 0.9f),
        FeeLabel("2%",   0.68f, 0.54f, 1.0f),
        FeeLabel("2.9%", 0.45f, 0.40f, 0.8f),
    )

    private var zeroAlpha = 0f
    private var zeroScale = 0.5f

    private var animStarted = false
    private var animScheduled = false
    private var animatorSet: AnimatorSet? = null
    private var startRunnable: Runnable? = null

    private val feePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }

    private val slashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#BBFFFFFF")
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val zeroPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return
        val cx = w / 2f
        val cy = h * 0.45f
        val s = w / 380f

        for (fee in fees) {
            if (fee.alpha < 0.01f) continue

            val textSize = 34f * fee.size * s
            feePaint.textSize = textSize
            slashPaint.strokeWidth = 2.5f * s

            val x = fee.baseX * w
            val y = fee.baseY * h

            // Measure text width for the slash line
            val textW = feePaint.measureText(fee.text)
            val fadeAlpha = fee.alpha * (1f - fee.destroyed)

            if (fee.destroyed > 0.01f) {
                // Split apart: top half up-left, bottom half down-right
                val drift = fee.destroyed * 20f * s
                val a = (fadeAlpha * 180).toInt().coerceIn(0, 255)

                // Top half
                canvas.save()
                canvas.clipRect(x - textW, y - textSize * 1.2f, x + textW, y - textSize * 0.1f)
                feePaint.alpha = a
                canvas.translate(-drift * 0.4f, -drift)
                canvas.drawText(fee.text, x, y, feePaint)
                canvas.restore()

                // Bottom half
                canvas.save()
                canvas.clipRect(x - textW, y - textSize * 0.1f, x + textW, y + textSize * 0.5f)
                feePaint.alpha = a
                canvas.translate(drift * 0.4f, drift)
                canvas.drawText(fee.text, x, y, feePaint)
                canvas.restore()
            } else {
                // Normal draw
                feePaint.alpha = (fee.alpha * 180).toInt().coerceIn(0, 255)
                canvas.drawText(fee.text, x, y, feePaint)
            }

            // Individual slash line across this fee
            if (fee.slashProgress > 0f && fee.destroyed < 0.99f) {
                val slashAlpha = ((1f - fee.destroyed) * 220).toInt()
                slashPaint.alpha = slashAlpha
                val sx = x - textW * 0.6f
                val sy = y + 4f * s
                val ex = x + textW * 0.6f
                val ey = y - textSize * 0.7f

                canvas.drawLine(
                    sx, sy,
                    sx + (ex - sx) * fee.slashProgress,
                    sy + (ey - sy) * fee.slashProgress,
                    slashPaint
                )
            }
        }

        // Draw "0%"
        if (zeroAlpha > 0.01f) {
            zeroPaint.textSize = 90f * s
            zeroPaint.alpha = (zeroAlpha * 255).toInt()
            canvas.save()
            canvas.scale(zeroScale, zeroScale, cx, cy)
            canvas.drawText("0%", cx, cy + 30f * s, zeroPaint)
            canvas.restore()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scheduleAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) scheduleAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        startRunnable?.let { removeCallbacks(it) }
        startRunnable = null
        animatorSet?.cancel()
    }

    private fun scheduleAnimation() {
        if (animScheduled || animStarted) return
        if (width == 0 || height == 0) return
        animScheduled = true
        startRunnable = Runnable { startAnimation() }
        postDelayed(startRunnable!!, 500)
    }

    private fun startAnimation() {
        if (animStarted) return
        animStarted = true

        val animators = mutableListOf<android.animation.Animator>()

        // Phase 1: All fees fade in (staggered)
        val fadeIn = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 600
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val p = it.animatedValue as Float
                fees.forEachIndexed { i, fee ->
                    val stagger = i * 0.12f
                    fee.alpha = ((p - stagger) / (1f - stagger)).coerceIn(0f, 1f)
                }
                invalidate()
            }
        }
        animators.add(fadeIn)

        // Phase 2: Each fee gets slashed individually (staggered)
        fees.forEachIndexed { i, fee ->
            val baseDelay = 1600L + i * 200L

            // Slash line draws
            val slash = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 200
                startDelay = baseDelay
                interpolator = AccelerateInterpolator()
                addUpdateListener {
                    fee.slashProgress = it.animatedValue as Float
                    invalidate()
                }
            }
            animators.add(slash)

            // Split + fade
            val destroy = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 350
                startDelay = baseDelay + 150L
                interpolator = AccelerateInterpolator(1.5f)
                addUpdateListener {
                    fee.destroyed = it.animatedValue as Float
                    invalidate()
                }
            }
            animators.add(destroy)
        }

        // Phase 3: "0%" bounces in after all fees are destroyed
        val lastFeeEnd = 1600L + (fees.size - 1) * 200L + 150L + 350L
        val zeroIn = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 600
            startDelay = lastFeeEnd + 300L
            interpolator = OvershootInterpolator(1.5f)
            addUpdateListener {
                val p = it.animatedValue as Float
                zeroAlpha = p
                zeroScale = 0.5f + 0.5f * p
                invalidate()
            }
        }
        animators.add(zeroIn)

        animatorSet = AnimatorSet().apply {
            playTogether(animators)
            start()
        }
    }
}
