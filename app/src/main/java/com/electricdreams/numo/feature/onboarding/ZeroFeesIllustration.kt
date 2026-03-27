package com.electricdreams.numo.feature.onboarding

import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

/**
 * Animated illustration for the "Zero Fees" explainer slide.
 * Simple sequence: "2%" shown → slash line draws across → "2%" fades out → "0%" fades in.
 */
class ZeroFeesIllustration @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var slashProgress = 0f   // 0..1 how much of the slash line is drawn
    private var feeAlpha = 1f        // "2%" opacity
    private var zeroAlpha = 0f       // "0%" opacity
    private var zeroScale = 0.7f     // "0%" scale

    private var animStarted = false
    private var animatorSet: AnimatorSet? = null

    private val feePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        alpha = 130
    }

    private val slashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        alpha = 180
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
        val cy = h / 2f
        val s = w / 380f

        feePaint.textSize = 72f * s
        zeroPaint.textSize = 82f * s
        slashPaint.strokeWidth = 3.5f * s

        // "2%" (fades out)
        if (feeAlpha > 0.01f) {
            feePaint.alpha = (feeAlpha * 130).toInt()
            canvas.drawText("2%", cx, cy + 24f * s, feePaint)
        }

        // Diagonal slash across the "2%"
        if (slashProgress > 0f && feeAlpha > 0.01f) {
            slashPaint.alpha = (feeAlpha * 180).toInt()
            val x1 = cx - 55f * s
            val y1 = cy + 48f * s
            val x2 = cx + 55f * s
            val y2 = cy - 10f * s
            canvas.drawLine(
                x1, y1,
                x1 + (x2 - x1) * slashProgress,
                y1 + (y2 - y1) * slashProgress,
                slashPaint
            )
        }

        // "0%" (fades in)
        if (zeroAlpha > 0.01f) {
            zeroPaint.alpha = (zeroAlpha * 255).toInt()
            canvas.save()
            canvas.scale(zeroScale, zeroScale, cx, cy + 24f * s)
            canvas.drawText("0%", cx, cy + 24f * s, zeroPaint)
            canvas.restore()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!animStarted) {
            postDelayed({ startAnimation() }, 600)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animatorSet?.cancel()
    }

    private fun startAnimation() {
        if (animStarted) return
        animStarted = true

        // Step 1: Slash draws across "2%" (0–400ms)
        val slash = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 400
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { slashProgress = it.animatedValue as Float; invalidate() }
        }

        // Step 2: "2%" + slash fade out (0–350ms after slash)
        val fadeOut = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 350
            interpolator = DecelerateInterpolator()
            addUpdateListener { feeAlpha = it.animatedValue as Float; invalidate() }
        }

        // Step 3: "0%" bounces in (0–500ms after fade)
        val zeroIn = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 500
            interpolator = OvershootInterpolator(1.5f)
            addUpdateListener {
                val p = it.animatedValue as Float
                zeroAlpha = p
                zeroScale = 0.7f + 0.3f * p
                invalidate()
            }
        }

        animatorSet = AnimatorSet().apply {
            play(slash)
            play(fadeOut).after(slash)
            play(zeroIn).after(fadeOut)
            start()
        }
    }
}
