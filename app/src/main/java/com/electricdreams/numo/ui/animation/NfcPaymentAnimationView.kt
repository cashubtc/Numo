package com.electricdreams.numo.ui.animation

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.electricdreams.numo.R
import kotlin.math.abs
import kotlin.math.min

/**
 * Full-screen animation for a bitcoin payment made by tapping a phone.
 *
 * While waiting it mirrors the card tap screen that Stripe draws, so both ways of tapping
 * look alike: a disc holding the contactless waves, in the screen's own colours, sending out
 * rings in bursts. The same disc then turns into the result circle, so nothing moves.
 */
class NfcPaymentAnimationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private enum class State {
        IDLE,
        READING,
        PROCESSING,
        RESULT_TRANSITION,
        RESULT,
    }

    private enum class ResultType {
        SUCCESS,
        ERROR,
    }

    private val colorIdle = ContextCompat.getColor(context, R.color.color_nfc_idle)
    private val colorSurface = ContextCompat.getColor(context, R.color.color_bg_white)
    private val colorForeground = ContextCompat.getColor(context, R.color.color_text_primary)
    private val colorDiscActive = ColorUtils.blendARGB(colorSurface, colorForeground, DISC_ACTIVE_BLEND)
    private val colorSuccess = ContextCompat.getColor(context, R.color.color_nfc_success)
    private val colorError = ContextCompat.getColor(context, R.color.color_error)

    private val argb = ArgbEvaluator()
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val discOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val resultIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private var state = State.IDLE
    private var resultType: ResultType? = null

    private var currentBackgroundColor = colorIdle

    /**
     * 0..1 looping, one burst per loop: a highlight runs out through the contactless waves,
     * then rings leave the disc one after another, growing and fading.
     */
    private var burstPhase = 0f

    /** 0 while reading, 1 once the payment is being processed: the disc fills in. */
    private var processingProgress = 0f

    /** 1 while waiting, 0 once the result has taken over. */
    private var waitAlpha = 0f

    /** 0 = waiting disc, 1 = white result circle. */
    private var morph = 0f
    private var resultIconProgress = 0f

    private var centerX = 0f

    /** Centre of the disc, placed where the card tap screen has its disc. */
    private var centerY = 0f
    private var discRadius = 0f

    /** The result icons are sized from this; it is the disc, which becomes the result circle. */
    private val resultCircleRadius: Float
        get() = discRadius

    private val waveBounds = RectF()
    private val checkPath = Path()
    private val checkPathMeasure = PathMeasure()
    private val checkPathSegment = Path()
    private var checkPathLength = 0f

    private var burstAnimator: ValueAnimator? = null
    private var processingAnimator: ValueAnimator? = null
    private var transitionAnimator: AnimatorSet? = null

    private var onResultDisplayedListener: ((Boolean) -> Unit)? = null

    /** Bottom edge of the disc, in this view's coordinates, for placing text below it. */
    val discBottom: Float
        get() = centerY + discRadius

    fun setOnResultDisplayedListener(listener: ((Boolean) -> Unit)?) {
        onResultDisplayedListener = listener
    }

    fun startReading() {
        cancelAnimations()

        state = State.READING
        resultType = null
        currentBackgroundColor = colorSurface
        burstPhase = 0f
        processingProgress = 0f
        waitAlpha = 1f
        morph = 0f
        resultIconProgress = 0f

        burstAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = BURST_PERIOD_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                burstPhase = animator.animatedValue as Float
                invalidate()
            }
            start()
        }

        invalidate()
    }

    fun startProcessing() {
        if (state != State.READING) return

        state = State.PROCESSING
        processingAnimator = ValueAnimator.ofFloat(processingProgress, 1f).apply {
            duration = 300L
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                processingProgress = animator.animatedValue as Float
                invalidate()
            }
            start()
        }
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
        burstPhase = 0f
        processingProgress = 0f
        waitAlpha = 0f
        morph = 0f
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

        val waitFadeAnimator = ValueAnimator.ofFloat(waitAlpha, 0f).apply {
            duration = 220L
            addUpdateListener { animator ->
                waitAlpha = animator.animatedValue as Float
                invalidate()
            }
        }

        val morphAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 360L
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { animator ->
                morph = animator.animatedValue as Float
                invalidate()
            }
        }

        val iconAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300L
            startDelay = 200L
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                resultIconProgress = animator.animatedValue as Float
                invalidate()
            }
        }

        transitionAnimator = AnimatorSet().apply {
            playTogether(colorAnimator, waitFadeAnimator, morphAnimator, iconAnimator)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    state = State.RESULT
                    val success = resultType == ResultType.SUCCESS
                    onResultDisplayedListener?.invoke(success)
                }
            })
            start()
        }

        burstAnimator?.cancel()
        processingAnimator?.cancel()
    }

    private fun cancelAnimations() {
        burstAnimator?.cancel()
        processingAnimator?.cancel()
        transitionAnimator?.cancel()
        burstAnimator = null
        processingAnimator = null
        transitionAnimator = null
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h * CENTER_Y

        discRadius = min(w, h) * DISC_RADIUS

        discOutlinePaint.strokeWidth = discRadius * DISC_OUTLINE_WIDTH
        wavePaint.strokeWidth = discRadius * WAVE_STROKE
        resultIconPaint.strokeWidth = discRadius * RESULT_ICON_STROKE
        resultIconPaint.color = colorSuccess

        // A soft glow that stays around the disc between bursts.
        glowPaint.shader = RadialGradient(
            centerX,
            centerY,
            discRadius * GLOW_RADIUS,
            intArrayOf(
                ColorUtils.setAlphaComponent(colorForeground, alphaOf(GLOW_ALPHA)),
                ColorUtils.setAlphaComponent(colorForeground, alphaOf(GLOW_ALPHA)),
                ColorUtils.setAlphaComponent(colorForeground, 0),
            ),
            floatArrayOf(0f, 1f / GLOW_RADIUS, 1f),
            Shader.TileMode.CLAMP,
        )

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

        if (waitAlpha > 0f) {
            glowPaint.alpha = alphaOf(waitAlpha)
            canvas.drawCircle(centerX, centerY, discRadius * GLOW_RADIUS, glowPaint)
            drawBurstRings(canvas)
        }

        val waitingFill = argb.evaluate(processingProgress, colorSurface, colorDiscActive) as Int
        discPaint.color = argb.evaluate(morph, waitingFill, Color.WHITE) as Int
        canvas.drawCircle(centerX, centerY, discRadius, discPaint)

        if (waitAlpha > 0f) {
            discOutlinePaint.color = colorForeground
            discOutlinePaint.alpha = alphaOf(waitAlpha * DISC_OUTLINE_ALPHA)
            canvas.drawCircle(centerX, centerY, discRadius - discOutlinePaint.strokeWidth / 2f, discOutlinePaint)
            drawContactlessWaves(canvas)
        }

        if (state == State.RESULT_TRANSITION || state == State.RESULT) {
            drawResultIcon(canvas)
        }
    }

    /**
     * Rings leave the disc one after another, slowing as they grow and fading as they go.
     * They are filled and overlap, so the screen is brightest near the disc and steps down
     * outwards, as on the card tap screen.
     */
    private fun drawBurstRings(canvas: Canvas) {
        val elapsedMs = burstPhase * BURST_PERIOD_MS
        ringPaint.color = colorForeground
        for (i in 0 until RING_COUNT) {
            val t = (elapsedMs - RING_FIRST_DELAY_MS - i * RING_STAGGER_MS) / RING_LIFE_MS
            if (t <= 0f || t >= 1f) continue
            val travelled = 1f - (1f - t) * (1f - t)
            val radius = discRadius * (1f + (RING_MAX_RADIUS - 1f) * travelled)
            ringPaint.alpha = alphaOf(waitAlpha * RING_ALPHA * (1f - t))
            canvas.drawCircle(centerX, centerY, radius, ringPaint)
        }
    }

    /** The contactless symbol's waves. A highlight runs outward through them while waiting. */
    private fun drawContactlessWaves(canvas: Canvas) {
        wavePaint.color = argb.evaluate(processingProgress, colorForeground, colorSurface) as Int
        val originX = centerX - discRadius * WAVE_ORIGIN_OFFSET
        for (i in 0 until WAVE_COUNT) {
            val waveRadius = discRadius * (WAVE_FIRST_RADIUS + WAVE_SPACING * i)
            // The highlight crosses the waves at the start of each burst, then they rest.
            val highlightAt = burstPhase * BURST_PERIOD_MS / WAVE_SWEEP_MS * (WAVE_COUNT + 1) - 1f
            val highlight = (1f - abs(highlightAt - i)).coerceIn(0f, 1f)
            wavePaint.alpha = alphaOf(waitAlpha * (WAVE_ALPHA_REST + (1f - WAVE_ALPHA_REST) * highlight))
            waveBounds.set(originX - waveRadius, centerY - waveRadius, originX + waveRadius, centerY + waveRadius)
            canvas.drawArc(waveBounds, -WAVE_HALF_SWEEP, WAVE_HALF_SWEEP * 2f, false, wavePaint)
        }
    }

    private fun alphaOf(fraction: Float): Int = (fraction * 255).toInt().coerceIn(0, 255)

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

    companion object {
        // Geometry and timing measured from the card tap screen. Sizes are fractions of the
        // view's width or multiples of the disc radius.
        private const val CENTER_Y = 0.417f
        private const val DISC_RADIUS = 0.26f
        private const val DISC_OUTLINE_WIDTH = 0.1f
        private const val DISC_OUTLINE_ALPHA = 0.38f
        private const val RESULT_ICON_STROKE = 0.075f

        /** How far the disc fills towards the foreground colour while processing. */
        private const val DISC_ACTIVE_BLEND = 0.8f

        private const val GLOW_RADIUS = 2.7f
        private const val GLOW_ALPHA = 0.12f

        private const val BURST_PERIOD_MS = 1800L
        private const val RING_COUNT = 3
        private const val RING_FIRST_DELAY_MS = 150f
        private const val RING_STAGGER_MS = 210f
        private const val RING_LIFE_MS = 1350f
        private const val RING_MAX_RADIUS = 3.7f
        private const val RING_ALPHA = 0.12f

        private const val WAVE_SWEEP_MS = 520f
        private const val WAVE_COUNT = 4
        private const val WAVE_ORIGIN_OFFSET = 0.3f
        private const val WAVE_FIRST_RADIUS = 0.14f
        private const val WAVE_SPACING = 0.13f
        private const val WAVE_STROKE = 0.055f
        private const val WAVE_HALF_SWEEP = 38f
        private const val WAVE_ALPHA_REST = 0.35f
    }
}
