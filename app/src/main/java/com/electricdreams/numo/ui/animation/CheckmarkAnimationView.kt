package com.electricdreams.numo.ui.animation

import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.electricdreams.numo.R
import com.electricdreams.numo.ui.util.isAnimationEnabled
import kotlin.math.min

/**
 * Animated checkmark matching the NFC payment success style:
 * white circle pops in, then green checkmark draws itself via PathMeasure.
 */
class CheckmarkAnimationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val colorSuccess = ContextCompat.getColor(context, R.color.color_success_green)

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = colorSuccess
    }

    private var centerX = 0f
    private var centerY = 0f
    private var circleRadius = 0f

    private val checkPath = Path()
    private val checkSegment = Path()
    private val checkMeasure = PathMeasure()
    private var checkLength = 0f

    private var circleScale = 0f
    private var checkProgress = 0f
    private var animator: AnimatorSet? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f

        val minSize = min(w, h).toFloat()
        circleRadius = minSize * 0.45f
        checkPaint.strokeWidth = minSize * 0.07f

        rebuildCheckPath()
    }

    private fun rebuildCheckPath() {
        val size = circleRadius * 0.9f
        val startX = centerX - size * 0.33f
        val startY = centerY + size * 0.06f
        val midX = centerX - size * 0.07f
        val midY = centerY + size * 0.31f
        val endX = centerX + size * 0.35f
        val endY = centerY - size * 0.26f

        checkPath.reset()
        checkPath.moveTo(startX, startY)
        checkPath.lineTo(midX, midY)
        checkPath.lineTo(endX, endY)

        checkMeasure.setPath(checkPath, false)
        checkLength = checkMeasure.length
    }

    fun play() {
        animator?.cancel()
        visibility = VISIBLE

        if (!context.isAnimationEnabled()) {
            circleScale = 1f
            checkProgress = 1f
            invalidate()
            return
        }

        circleScale = 0f
        checkProgress = 0f

        val circleAnim = ValueAnimator.ofFloat(0.82f, 1f).apply {
            duration = 320L
            interpolator = android.view.animation.PathInterpolator(0.175f, 0.885f, 0.32f, 1.1f)
            addUpdateListener {
                circleScale = it.animatedValue as Float
                invalidate()
            }
        }

        val checkAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300L
            startDelay = 120L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                checkProgress = it.animatedValue as Float
                invalidate()
            }
        }

        animator = AnimatorSet().apply {
            playTogether(circleAnim, checkAnim)
            start()
        }
    }

    /**
     * Fade out and scale down (subtler than entrance per Jakub's exit principle).
     * Hides the view on completion.
     */
    fun dismiss(onComplete: (() -> Unit)? = null) {
        animator?.cancel()
        animate()
            .alpha(0f)
            .scaleX(0.95f)
            .scaleY(0.95f)
            .setDuration(200)
            .withEndAction {
                visibility = GONE
                onComplete?.invoke()
            }
            .start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (circleScale > 0f) {
            canvas.drawCircle(centerX, centerY, circleRadius * circleScale, circlePaint)
        }

        if (checkProgress > 0f) {
            checkSegment.reset()
            checkMeasure.getSegment(0f, checkLength * checkProgress, checkSegment, true)
            canvas.drawPath(checkSegment, checkPaint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }
}
