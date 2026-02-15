package com.electricdreams.numo.ui.animation

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.electricdreams.numo.R
import kotlin.math.min

class NfcPaymentAnimationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private enum class State {
        IDLE,
        READING,
        RESULT_TRANSITION,
        RESULT,
    }

    private enum class ResultType {
        SUCCESS,
        ERROR,
    }

    private val colorIdle = Color.parseColor("#0A0A0A")
    private val colorReading = ContextCompat.getColor(context, R.color.color_bitcoin_orange)
    private val colorSuccess = Color.parseColor("#34C759")
    private val colorSuccessGradientStart = Color.parseColor("#22C55E")
    private val colorSuccessGradientEnd = Color.parseColor("#4ADE80")
    private val colorError = ContextCompat.getColor(context, R.color.color_error)

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val successGradientPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val spinnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }
    private val resultCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val resultIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private var state = State.IDLE
    private var resultType: ResultType? = null

    private var currentBackgroundColor = colorIdle
    private var spinnerRotation = 0f
    private var spinnerPulse = 1f
    private var spinnerAlpha = 0f
    private var resultCircleScale = 0f
    private var resultIconProgress = 0f

    private var centerX = 0f
    private var centerY = 0f
    private var spinnerRadius = 0f
    private var spinnerStrokeWidth = 0f
    private var resultCircleRadius = 0f
    private var successGradient: LinearGradient? = null

    private val checkPath = Path()
    private val checkPathMeasure = PathMeasure()
    private val checkPathSegment = Path()
    private var checkPathLength = 0f

    private var spinAnimator: ValueAnimator? = null
    private var pulseAnimator: ValueAnimator? = null
    private var transitionAnimator: AnimatorSet? = null

    private var onResultDisplayedListener: ((Boolean) -> Unit)? = null

    fun setOnResultDisplayedListener(listener: ((Boolean) -> Unit)?) {
        onResultDisplayedListener = listener
    }

    fun startReading() {
        cancelAnimations()

        state = State.READING
        resultType = null
        currentBackgroundColor = colorReading
        spinnerRotation = 0f
        spinnerPulse = 1f
        spinnerAlpha = 1f
        resultCircleScale = 0f
        resultIconProgress = 0f

        spinAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 1100L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                spinnerRotation = animator.animatedValue as Float
                invalidate()
            }
            start()
        }

        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1200L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                val progress = animator.animatedValue as Float
                spinnerPulse = 1f + (progress * 0.08f)
                invalidate()
            }
            start()
        }

        invalidate()
    }

    fun showSuccess(_amountText: String) {
        startResultTransition(ResultType.SUCCESS)
    }

    fun showError(_message: String) {
        startResultTransition(ResultType.ERROR)
    }

    fun reset() {
        cancelAnimations()
        state = State.IDLE
        resultType = null
        currentBackgroundColor = colorIdle
        spinnerRotation = 0f
        spinnerPulse = 1f
        spinnerAlpha = 0f
        resultCircleScale = 0f
        resultIconProgress = 0f
        invalidate()
    }

    private fun startResultTransition(target: ResultType) {
        if (state == State.IDLE) {
            startReading()
        }

        if (resultType != null) {
            return
        }

        state = State.RESULT_TRANSITION
        resultType = target

        val targetColor = when (target) {
            ResultType.SUCCESS -> colorSuccess
            ResultType.ERROR -> colorError
        }

        transitionAnimator?.cancel()

        val colorAnimator = ValueAnimator.ofObject(ArgbEvaluator(), currentBackgroundColor, targetColor).apply {
            duration = 360L
            addUpdateListener { animator ->
                currentBackgroundColor = animator.animatedValue as Int
                invalidate()
            }
        }

        val spinnerFadeAnimator = ValueAnimator.ofFloat(spinnerAlpha, 0f).apply {
            duration = 280L
            addUpdateListener { animator ->
                spinnerAlpha = animator.animatedValue as Float
                invalidate()
            }
        }

        val circleAnimator = ValueAnimator.ofFloat(0.82f, 1f).apply {
            duration = 320L
            addUpdateListener { animator ->
                resultCircleScale = animator.animatedValue as Float
                invalidate()
            }
        }

        val iconAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300L
            startDelay = 120L
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                resultIconProgress = animator.animatedValue as Float
                invalidate()
            }
        }

        transitionAnimator = AnimatorSet().apply {
            playTogether(colorAnimator, spinnerFadeAnimator, circleAnimator, iconAnimator)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    state = State.RESULT
                    val success = resultType == ResultType.SUCCESS
                    onResultDisplayedListener?.invoke(success)
                }
            })
            start()
        }

        spinAnimator?.cancel()
        pulseAnimator?.cancel()
    }

    private fun cancelAnimations() {
        spinAnimator?.cancel()
        pulseAnimator?.cancel()
        transitionAnimator?.cancel()
        spinAnimator = null
        pulseAnimator = null
        transitionAnimator = null
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h * 0.55f

        val minSize = min(w, h).toFloat()
        spinnerRadius = dpToPx(38f).coerceAtMost(minSize * 0.24f)
        spinnerStrokeWidth = dpToPx(5f)
        resultCircleRadius = dpToPx(72f).coerceAtMost(minSize * 0.31f)

        spinnerPaint.strokeWidth = spinnerStrokeWidth
        resultIconPaint.strokeWidth = dpToPx(6f)
        resultIconPaint.color = colorSuccess
        successGradient = LinearGradient(
            0f,
            0f,
            w.toFloat(),
            h.toFloat(),
            colorSuccessGradientStart,
            colorSuccessGradientEnd,
            Shader.TileMode.CLAMP,
        )
        successGradientPaint.shader = successGradient

        rebuildCheckPath()
    }

    private fun rebuildCheckPath() {
        val size = resultCircleRadius * 0.9f
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

        checkPathMeasure.setPath(checkPath, false)
        checkPathLength = checkPathMeasure.length
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (state == State.IDLE) {
            return
        }

        backgroundPaint.color = currentBackgroundColor
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        if (resultType == ResultType.SUCCESS &&
            (state == State.RESULT_TRANSITION || state == State.RESULT) &&
            successGradient != null
        ) {
            successGradientPaint.alpha = when (state) {
                State.RESULT -> 255
                State.RESULT_TRANSITION -> (resultIconProgress * 255).toInt().coerceIn(0, 255)
                else -> 0
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), successGradientPaint)
        }

        if (state == State.READING || state == State.RESULT_TRANSITION) {
            val radius = spinnerRadius * spinnerPulse
            val rect = RectF(
                centerX - radius,
                centerY - radius,
                centerX + radius,
                centerY + radius,
            )

            spinnerPaint.alpha = (spinnerAlpha * 255).toInt().coerceIn(0, 255)
            canvas.save()
            canvas.rotate(spinnerRotation, centerX, centerY)
            canvas.drawArc(rect, 0f, 300f, false, spinnerPaint)
            canvas.restore()
        }

        if (state == State.RESULT_TRANSITION || state == State.RESULT) {
            if (resultCircleScale > 0f) {
                canvas.drawCircle(
                    centerX,
                    centerY,
                    resultCircleRadius * resultCircleScale,
                    resultCirclePaint,
                )
            }
            drawResultIcon(canvas)
        }
    }

    private fun drawResultIcon(canvas: Canvas) {
        val result = resultType ?: return
        if (resultIconProgress <= 0f) return

        when (result) {
            ResultType.SUCCESS -> {
                resultIconPaint.color = colorSuccess
                checkPathSegment.reset()
                checkPathMeasure.getSegment(
                    0f,
                    checkPathLength * resultIconProgress,
                    checkPathSegment,
                    true,
                )
                canvas.drawPath(checkPathSegment, resultIconPaint)
            }
            ResultType.ERROR -> {
                resultIconPaint.color = colorError
                val arm = resultCircleRadius * 0.42f
                val firstLineProgress = min(1f, resultIconProgress * 2f)
                val secondLineProgress = min(1f, maxOf(0f, (resultIconProgress - 0.5f) * 2f))

                val x1 = centerX - arm
                val y1 = centerY - arm
                val x2 = centerX + arm
                val y2 = centerY + arm
                val x3 = centerX - arm
                val y3 = centerY + arm
                val x4 = centerX + arm
                val y4 = centerY - arm

                canvas.drawLine(
                    x1,
                    y1,
                    x1 + ((x2 - x1) * firstLineProgress),
                    y1 + ((y2 - y1) * firstLineProgress),
                    resultIconPaint,
                )
                canvas.drawLine(
                    x3,
                    y3,
                    x3 + ((x4 - x3) * secondLineProgress),
                    y3 + ((y4 - y3) * secondLineProgress),
                    resultIconPaint,
                )
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        reset()
    }

    private fun dpToPx(dp: Float): Float = dp * resources.displayMetrics.density
}
