package com.electricdreams.numo.ui.animation

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import androidx.core.animation.doOnEnd
import androidx.core.content.ContextCompat
import com.electricdreams.numo.R
import kotlin.math.min

/**
 * Full-screen NFC payment animation with four phases:
 * 1) Orange fullscreen takeover
 * 2) Drop-in spinner while processing
 * 3) Zoom-in transition
 * 4) Green success + checkmark
 */
class NfcPaymentAnimationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Animation phases for clear state tracking.
    enum class Phase {
        IDLE,
        INITIATION,
        PROCESSING,
        ZOOM,
        SUCCESS
    }

    private val colorOrange = ContextCompat.getColor(context, R.color.color_bitcoin_orange)
    private val colorGreen = ContextCompat.getColor(context, R.color.color_success_green)

    // Paint objects are reused for smooth 60fps rendering.
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val spinnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }

    // Geometric state for drawing the spinner/checkmark.
    private var centerX = 0f
    private var centerY = 0f
    private var spinnerRadius = 0f
    private var spinnerStrokeWidth = 0f
    private var checkStrokeWidth = 0f
    private var checkPath = Path()
    private var checkPathMeasure = PathMeasure()
    private var checkPathSegment = Path()
    private var checkPathLength = 0f

    // Animation state values.
    private var phase = Phase.IDLE
    private var backgroundScale = 0f
    private var backgroundColor = colorOrange
    private var spinnerY = 0f
    private var spinnerRotation = 0f
    private var spinnerScale = 1f
    private var spinnerAlpha = 1f
    private var checkProgress = 0f
    private var isSuccessSequence = false

    // Animators per phase for easy cancellation.
    private var initiationAnimator: ValueAnimator? = null
    private var dropAnimator: ValueAnimator? = null
    private var rotationAnimator: ValueAnimator? = null
    private var zoomAnimator: ValueAnimator? = null
    private var colorAnimator: ValueAnimator? = null
    private var checkAnimator: ValueAnimator? = null
    private var holdAnimator: ValueAnimator? = null

    private var onCompletionListener: (() -> Unit)? = null

    /**
     * Start the NFC animation from the beginning.
     */
    fun start() {
        if (width == 0 || height == 0) {
            post { start() }
            return
        }
        resetInternal()
        visibility = VISIBLE
        startInitiationPhase()
    }

    /**
     * Trigger the success phase and call back once the animation finishes.
     */
    fun playSuccess(onComplete: () -> Unit) {
        if (isSuccessSequence) return
        isSuccessSequence = true
        onCompletionListener = onComplete
        startZoomPhase()
    }

    /**
     * Stop the animation immediately and hide the view.
     */
    fun stopAndReset() {
        resetInternal()
        visibility = GONE
        invalidate()
    }

    /**
     * Indicates whether the success sequence is currently running.
     */
    fun isSuccessInProgress(): Boolean = isSuccessSequence

    /**
     * Indicates whether any animation is currently active.
     */
    fun isAnimating(): Boolean = phase != Phase.IDLE

    private fun startInitiationPhase() {
        phase = Phase.INITIATION
        backgroundColor = colorOrange
        backgroundScale = 0f
        spinnerAlpha = 1f
        checkProgress = 0f

        initiationAnimator?.cancel()
        initiationAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420
            interpolator = PathInterpolator(0.25f, 0.46f, 0.45f, 0.94f)
            addUpdateListener { animator ->
                backgroundScale = animator.animatedValue as Float
                invalidate()
            }
            doOnEnd { startProcessingPhase() }
            start()
        }
    }

    private fun startProcessingPhase() {
        phase = Phase.PROCESSING
        spinnerY = -spinnerRadius * 2f
        backgroundScale = 1f

        startSpinnerRotation()

        dropAnimator?.cancel()
        dropAnimator = ValueAnimator.ofFloat(spinnerY, centerY).apply {
            duration = 700
            interpolator = PathInterpolator(0.34f, 1.56f, 0.64f, 1f)
            addUpdateListener { animator ->
                spinnerY = animator.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun startSpinnerRotation() {
        rotationAnimator?.cancel()
        rotationAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 2200
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                spinnerRotation = animator.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun startZoomPhase() {
        if (phase == Phase.IDLE) {
            return
        }
        phase = Phase.ZOOM
        initiationAnimator?.cancel()
        spinnerY = centerY
        dropAnimator?.cancel()

        zoomAnimator?.cancel()
        zoomAnimator = ValueAnimator.ofFloat(1f, 4f).apply {
            duration = 1000
            interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
            addUpdateListener { animator ->
                spinnerScale = animator.animatedValue as Float
                spinnerAlpha = 1f - ((spinnerScale - 1f) / 3f).coerceIn(0f, 1f)
                invalidate()
            }
            doOnEnd {
                rotationAnimator?.cancel()
                startSuccessPhase()
            }
            start()
        }
    }

    private fun startSuccessPhase() {
        phase = Phase.SUCCESS
        spinnerAlpha = 0f

        colorAnimator?.cancel()
        colorAnimator = ValueAnimator.ofObject(ArgbEvaluator(), colorOrange, colorGreen).apply {
            duration = 500
            interpolator = PathInterpolator(0.25f, 0.46f, 0.45f, 0.94f)
            addUpdateListener { animator ->
                backgroundColor = animator.animatedValue as Int
                invalidate()
            }
            start()
        }

        checkAnimator?.cancel()
        checkAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420
            interpolator = PathInterpolator(0.34f, 1.56f, 0.64f, 1f)
            addUpdateListener { animator ->
                checkProgress = animator.animatedValue as Float
                invalidate()
            }
            start()
        }

        holdAnimator?.cancel()
        holdAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1200
            addUpdateListener { invalidate() }
            doOnEnd { finishSuccess() }
            start()
        }
    }

    private fun finishSuccess() {
        val callback = onCompletionListener
        resetInternal()
        visibility = GONE
        callback?.invoke()
    }

    private fun resetInternal() {
        initiationAnimator?.cancel()
        dropAnimator?.cancel()
        rotationAnimator?.cancel()
        zoomAnimator?.cancel()
        colorAnimator?.cancel()
        checkAnimator?.cancel()
        holdAnimator?.cancel()

        phase = Phase.IDLE
        backgroundScale = 0f
        backgroundColor = colorOrange
        spinnerRotation = 0f
        spinnerScale = 1f
        spinnerAlpha = 1f
        checkProgress = 0f
        isSuccessSequence = false
        onCompletionListener = null
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f

        val minSize = min(w, h).toFloat()
        spinnerRadius = dpToPx(60f).coerceAtMost(minSize * 0.28f)
        spinnerStrokeWidth = dpToPx(8f)
        checkStrokeWidth = dpToPx(10f)

        spinnerPaint.strokeWidth = spinnerStrokeWidth
        checkPaint.strokeWidth = checkStrokeWidth

        rebuildCheckPath()
    }

    private fun rebuildCheckPath() {
        val checkSize = spinnerRadius * 1.1f
        val startX = centerX - checkSize * 0.5f
        val startY = centerY + checkSize * 0.05f
        val midX = centerX - checkSize * 0.15f
        val midY = centerY + checkSize * 0.35f
        val endX = centerX + checkSize * 0.55f
        val endY = centerY - checkSize * 0.35f

        checkPath.reset()
        checkPath.moveTo(startX, startY)
        checkPath.lineTo(midX, midY)
        checkPath.lineTo(endX, endY)

        checkPathMeasure.setPath(checkPath, false)
        checkPathLength = checkPathMeasure.length
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (phase == Phase.IDLE) return

        // Draw the background takeover.
        backgroundPaint.color = backgroundColor
        if (phase == Phase.INITIATION) {
            canvas.save()
            canvas.scale(backgroundScale, backgroundScale, centerX, centerY)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
            canvas.restore()
        } else {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        }

        // Draw the spinner during processing and zoom.
        if (phase == Phase.PROCESSING || phase == Phase.ZOOM) {
            val radius = spinnerRadius * spinnerScale
            val rect = RectF(
                centerX - radius,
                spinnerY - radius,
                centerX + radius,
                spinnerY + radius
            )
            spinnerPaint.alpha = (spinnerAlpha * 255).toInt().coerceIn(0, 255)
            canvas.save()
            canvas.rotate(spinnerRotation, centerX, spinnerY)
            canvas.drawArc(rect, 0f, 270f, false, spinnerPaint)
            canvas.restore()
        }

        // Draw the success checkmark.
        if (phase == Phase.SUCCESS && checkProgress > 0f) {
            checkPathSegment.reset()
            checkPathMeasure.getSegment(0f, checkPathLength * checkProgress, checkPathSegment, true)
            canvas.drawPath(checkPathSegment, checkPaint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        resetInternal()
    }

    private fun dpToPx(dp: Float): Float {
        return dp * resources.displayMetrics.density
    }
}


