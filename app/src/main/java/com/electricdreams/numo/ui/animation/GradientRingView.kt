package com.electricdreams.numo.ui.animation

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.PathInterpolator
import androidx.core.content.ContextCompat
import com.electricdreams.numo.R

/**
 * Draws a gradient-stroked arc that spins around the view's center.
 * Used on the default-mint hero card to animate a mint swap.
 */
class GradientRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val strokePx = 2.5f * density
    private val ringColor = ContextCompat.getColor(context, R.color.numo_fluorescent_green)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx
        strokeCap = Paint.Cap.BUTT
    }

    private val rect = RectF()
    private var rotation = 0f
    private var animator: ValueAnimator? = null

    init {
        visibility = INVISIBLE
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val inset = strokePx / 2f
        rect.set(inset, inset, w - inset, h - inset)

        // Comet-tail gradient: transparent tail → green head, with clean seam
        paint.shader = SweepGradient(
            w / 2f, h / 2f,
            intArrayOf(Color.TRANSPARENT, ringColor, ringColor, Color.TRANSPARENT),
            floatArrayOf(0f, 0.7f, 0.85f, 1f)
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (visibility != VISIBLE) return
        canvas.save()
        canvas.rotate(rotation, width / 2f, height / 2f)
        canvas.drawArc(rect, 0f, 280f, false, paint)
        canvas.restore()
    }

    /**
     * Spin one full revolution, then fade out and call [onComplete].
     */
    fun spin(onComplete: () -> Unit) {
        animator?.cancel()
        rotation = 0f
        alpha = 1f
        visibility = VISIBLE

        animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 800L
            // Fast start, smooth deceleration — feels like momentum, not a machine
            interpolator = PathInterpolator(0.4f, 0f, 0.0f, 1f)
            addUpdateListener {
                rotation = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    animate()
                        .alpha(0f)
                        .setDuration(200)
                        .withEndAction {
                            visibility = INVISIBLE
                            onComplete()
                        }
                        .start()
                }
            })
            start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }
}
