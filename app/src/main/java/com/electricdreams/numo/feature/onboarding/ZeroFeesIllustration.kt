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
 * Phase 1: Various fee percentages float around gently.
 * Phase 2: A slash cuts through them, they split and fade away.
 * Phase 3: A large "0%" appears with a bounce.
 */
class ZeroFeesIllustration @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private data class FeeLabel(
        val text: String,
        val baseX: Float,   // 0..1 relative position
        val baseY: Float,
        val size: Float,    // relative text size multiplier
        var alpha: Float = 0f,
        var splitOffset: Float = 0f  // drift apart when slashed
    )

    private val fees = listOf(
        FeeLabel("3%",   0.20f, 0.30f, 1.1f),
        FeeLabel("2.5%", 0.72f, 0.25f, 0.85f),
        FeeLabel("1.5%", 0.15f, 0.60f, 0.9f),
        FeeLabel("2%",   0.65f, 0.55f, 1.0f),
        FeeLabel("2.9%", 0.45f, 0.40f, 0.75f),
    )

    private var feesAlpha = 0f       // overall fee labels opacity
    private var slashProgress = 0f   // 0..1
    private var feeSplitDrift = 0f   // how far fee halves drift apart
    private var zeroAlpha = 0f
    private var zeroScale = 0.5f

    private var animStarted = false
    private var animatorSet: AnimatorSet? = null

    private val feePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }

    private val slashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#99FFFFFF")
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
        val cx = w / 2f
        val cy = h * 0.45f
        val s = w / 380f

        // Draw floating fee labels
        if (feesAlpha > 0.01f) {
            for (fee in fees) {
                val textSize = 32f * fee.size * s
                feePaint.textSize = textSize
                val x = fee.baseX * w
                val y = fee.baseY * h

                val a = (feesAlpha * fee.alpha * 180).toInt().coerceIn(0, 255)

                if (feeSplitDrift > 0) {
                    // Split: top half drifts up-left, bottom half drifts down-right
                    val drift = feeSplitDrift * 25f * s

                    // Top half
                    canvas.save()
                    canvas.clipRect(0f, 0f, w, y)
                    feePaint.alpha = a
                    canvas.translate(-drift * 0.5f, -drift)
                    canvas.drawText(fee.text, x, y, feePaint)
                    canvas.restore()

                    // Bottom half
                    canvas.save()
                    canvas.clipRect(0f, y, w, h)
                    feePaint.alpha = a
                    canvas.translate(drift * 0.5f, drift)
                    canvas.drawText(fee.text, x, y, feePaint)
                    canvas.restore()
                } else {
                    feePaint.alpha = a
                    canvas.drawText(fee.text, x, y, feePaint)
                }
            }

            // Diagonal slash line across the center
            if (slashProgress > 0f) {
                slashPaint.strokeWidth = 3f * s
                slashPaint.alpha = ((1f - feeSplitDrift.coerceAtMost(1f)) * 200).toInt()
                val slashW = w * 0.7f
                val x1 = cx - slashW / 2f
                val y1 = cy + 30f * s
                val x2 = cx + slashW / 2f
                val y2 = cy - 30f * s

                canvas.drawLine(
                    x1, y1,
                    x1 + (x2 - x1) * slashProgress,
                    y1 + (y2 - y1) * slashProgress,
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
        if (!animStarted) {
            postDelayed({ startAnimation() }, 500)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animatorSet?.cancel()
    }

    private fun startAnimation() {
        if (animStarted) return
        animStarted = true

        // Phase 1: Fee labels fade in (staggered)
        val fadeIn = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 800
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val p = it.animatedValue as Float
                feesAlpha = p
                fees.forEachIndexed { i, fee ->
                    val stagger = (i * 0.15f).coerceAtMost(0.6f)
                    fee.alpha = ((p - stagger) / (1f - stagger)).coerceIn(0f, 1f)
                }
                invalidate()
            }
        }

        // Phase 2: Slash draws across
        val slash = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 350
            startDelay = 1000
            interpolator = AccelerateInterpolator(1.2f)
            addUpdateListener {
                slashProgress = it.animatedValue as Float
                invalidate()
            }
        }

        // Phase 3: Fees split apart and fade out
        val splitFade = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 500
            startDelay = 1400
            interpolator = AccelerateInterpolator()
            addUpdateListener {
                val p = it.animatedValue as Float
                feeSplitDrift = p
                feesAlpha = 1f - p
                invalidate()
            }
        }

        // Phase 4: "0%" bounces in
        val zeroIn = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 600
            startDelay = 1800
            interpolator = OvershootInterpolator(1.5f)
            addUpdateListener {
                val p = it.animatedValue as Float
                zeroAlpha = p
                zeroScale = 0.5f + 0.5f * p
                invalidate()
            }
        }

        animatorSet = AnimatorSet().apply {
            playTogether(fadeIn, slash, splitFade, zeroIn)
            start()
        }
    }
}
