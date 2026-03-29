package com.electricdreams.numo.feature.onboarding

import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import com.electricdreams.numo.ui.util.isAnimationEnabled

/**
 * Animated "Zero Fees" illustration.
 * Large "0%" fades and bounces in, then breathes with a gentle pulse loop.
 */
class ZeroFeesIllustration @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var zeroAlpha = 0f
    private var zeroScale = 0.5f

    private var animStarted = false
    private var animScheduled = false
    private var animatorSet: AnimatorSet? = null
    private var pulseAnimator: ValueAnimator? = null
    private var startRunnable: Runnable? = null

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
        animatorSet?.removeAllListeners()
        animatorSet?.cancel()
        animatorSet = null
        pulseAnimator?.removeAllListeners()
        pulseAnimator?.cancel()
        pulseAnimator = null
        animStarted = false
        animScheduled = false
        zeroAlpha = 0f
        zeroScale = 0.5f
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

        if (!context.isAnimationEnabled()) {
            zeroScale = 1f; zeroAlpha = 1f
            invalidate()
            return
        }

        val zeroIn = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 600
            interpolator = OvershootInterpolator(1.0f)
            addUpdateListener {
                val p = it.animatedValue as Float
                zeroAlpha = p
                zeroScale = 0.5f + 0.5f * p
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    startBreathingPulse()
                }
            })
        }

        animatorSet = AnimatorSet().apply {
            play(zeroIn)
            start()
        }
    }

    private fun startBreathingPulse() {
        if (!isAttachedToWindow) return
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val p = it.animatedValue as Float
                zeroScale = 1f + 0.08f * p
                invalidate()
            }
            start()
        }
    }
}
