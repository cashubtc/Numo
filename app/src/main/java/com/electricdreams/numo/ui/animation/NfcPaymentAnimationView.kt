package com.electricdreams.numo.ui.animation

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
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
import android.view.animation.PathInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withScale
import androidx.core.graphics.withTranslation
import com.electricdreams.numo.R
import com.electricdreams.numo.ui.util.isAnimationEnabled
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Full-screen canvas animation for tap-to-pay waiting, processing, and payment results.
 *
 * While waiting it mirrors the card tap screen that Stripe draws, so both ways of tapping
 * look alike: a disc holding the contactless waves inside a faint ring, with pulses spreading
 * out from behind it. Once a payment arrives the disc fills and grows into the ring while a
 * calm orbiting arc runs around its rim so long network swaps or card confirmations stay
 * visibly alive. The result's colour then spreads from the disc, which becomes the result
 * circle, so nothing moves.
 *
 * Non-NFC completions (QR / Lightning / Arkoor) enter the result state directly without ever
 * flashing the contactless waiting disc or pulses.
 */
class NfcPaymentAnimationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    internal enum class State {
        IDLE,
        SURFACE_COVER,
        READING,
        PROCESSING,
        RESULT_TRANSITION,
        RESULT,
    }

    private enum class ResultType {
        SUCCESS,
        ERROR,
    }

    private val colorSurface = ContextCompat.getColor(context, R.color.color_tap_screen_bg)

    /** The colour Stripe's tap screen is given as its primary: the ring, pulses, and orbit rim. */
    private val colorForeground = ContextCompat.getColor(context, R.color.color_primary_green)
    private val colorSymbol = ContextCompat.getColor(context, R.color.color_tap_symbol)
    private val colorSymbolActive = ContextCompat.getColor(context, R.color.color_tap_symbol_active)
    private val colorDiscActive = ContextCompat.getColor(context, R.color.color_tap_disc_active)
    private val colorSuccess = ContextCompat.getColor(context, R.color.color_nfc_success)
    private val colorError = ContextCompat.getColor(context, R.color.color_error)

    private val argb = ArgbEvaluator()
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val floodPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        color = Color.BLACK
    }
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val discOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val orbitTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val orbitArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val resultIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** Starts fast and settles gently: for things arriving on screen. */
    private val enterInterpolator = PathInterpolator(0.23f, 1f, 0.32f, 1f)
    private val iconDrawInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)

    internal var currentState: State = State.IDLE
        private set
    private var resultType: ResultType? = null

    private var currentBackgroundColor = Color.TRANSPARENT

    /** 0..1 looping, one set of pulses per loop, leaving the disc one after another. */
    private var burstPhase = 0f

    /** 1 while the pulses ask for a tap, 0 once a payment has been read. */
    internal var pulseAlpha = 0f
        private set

    /** 0 while reading, 1 once the payment is being processed: the disc grows into its ring. */
    internal var processingProgress = 0f
        private set

    /** 0..1 looping rotation phase for the subtle orbiting rim arc while processing. */
    private var orbitPhase = 0f

    /** 0..1 visibility of the orbiting rim arc during processing. */
    internal var orbitAlpha = 0f
        private set

    /** 1 while waiting/processing, 0 once the result has taken over. */
    internal var waitAlpha = 0f
        private set

    /** 0 = waiting/processing disc, 1 = white result circle. */
    private var morph = 0f

    /** How far the disc had grown into its ring when the result arrived. */
    private var grownAtResult = 0f

    /** 0..1: the result's colour spreading from the disc/centre to the screen's corners. */
    private var floodProgress = 0f
    private var floodStartFromCenter = false
    internal var resultIconProgress = 0f
        private set

    /** 0..1 as the disc settles into place when the screen first appears. */
    private var enterProgress = 1f
    private var activeEnterScale = ENTER_SCALE

    private var centerX = 0f

    /** Centre of the disc, placed where the card tap screen has its disc. */
    private var centerY = 0f
    private var discRadius = 0f
    private var floodMaxRadius = 0f

    /**
     * The ring's outer edge. The disc sits inside it while waiting and fills it while
     * processing; the result icons are sized from it, as the disc becomes the result circle.
     */
    private val resultCircleRadius: Float
        get() = discRadius

    private val waveBounds = RectF()
    private val orbitBounds = RectF()
    private val checkPath = Path()
    private val checkPathMeasure = PathMeasure()
    private val checkPathSegment = Path()
    private var checkPathLength = 0f

    /** The disc's shadow, blurred once: blurring it afresh on every frame is costly. */
    private var shadowBitmap: Bitmap? = null

    private var burstAnimator: ValueAnimator? = null
    private var enterAnimator: ValueAnimator? = null
    private var pulseFadeAnimator: ValueAnimator? = null
    private var processingAnimator: ValueAnimator? = null
    private var orbitAnimator: ValueAnimator? = null
    private var orbitFadeAnimator: ValueAnimator? = null
    private var transitionAnimator: AnimatorSet? = null

    private var onResultDisplayedListener: ((Boolean) -> Unit)? = null
    private var onGeometryChangedListener: (() -> Unit)? = null

    /** Bottom edge of the disc (including the orbiting rim halo), in this view's coordinates. */
    val discBottom: Float
        get() = centerY + discRadius

    fun setOnResultDisplayedListener(listener: ((Boolean) -> Unit)?) {
        onResultDisplayedListener = listener
    }

    fun setOnGeometryChangedListener(listener: (() -> Unit)?) {
        onGeometryChangedListener = listener
    }

    /**
     * Covers checkout with the matching tap surface colour without drawing a disc or pulses.
     * Used behind Stripe's Tap to Pay Activity so checkout never flashes while Stripe closes.
     */
    fun showSurfaceCover() {
        cancelAnimations()
        currentState = State.SURFACE_COVER
        resultType = null
        currentBackgroundColor = colorSurface
        burstPhase = 0f
        pulseAlpha = 0f
        processingProgress = 0f
        orbitPhase = 0f
        orbitAlpha = 0f
        waitAlpha = 0f
        morph = 0f
        grownAtResult = 0f
        floodProgress = 0f
        floodStartFromCenter = false
        resultIconProgress = 0f
        enterProgress = 1f
        activeEnterScale = ENTER_SCALE
        invalidate()
    }

    /** Starts waiting for a tap. With [animateIn] the disc settles into place as it appears. */
    fun startReading(animateIn: Boolean = false) {
        cancelAnimations()

        val animate = context.isAnimationEnabled()
        currentState = State.READING
        resultType = null
        currentBackgroundColor = colorSurface
        burstPhase = 0f
        // With animations switched off the pulses would freeze mid-flight, so they stay away.
        pulseAlpha = if (animate) 1f else 0f
        processingProgress = 0f
        orbitPhase = 0f
        orbitAlpha = 0f
        waitAlpha = 1f
        morph = 0f
        grownAtResult = 0f
        floodProgress = 0f
        floodStartFromCenter = false
        resultIconProgress = 0f
        enterProgress = 1f
        activeEnterScale = ENTER_SCALE

        if (animate) {
            burstAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = PULSE_PERIOD_MS
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { animator ->
                    burstPhase = animator.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        if (animate && animateIn) {
            startEnterSettle(ENTER_SCALE, ENTER_MS)
        }

        invalidate()
    }

    /**
     * Transitions from waiting into processing: the disc fills and grows into its ring, the
     * tap pulses fade away, and a subtle orbiting rim arc fades in around the disc perimeter.
     */
    fun startProcessing() {
        if (currentState == State.PROCESSING ||
            currentState == State.RESULT_TRANSITION ||
            currentState == State.RESULT
        ) {
            return
        }
        if (currentState != State.READING) {
            startProcessingDirectly(animateIn = true)
            return
        }

        val animate = context.isAnimationEnabled()
        currentState = State.PROCESSING

        if (!animate) {
            burstAnimator?.cancel()
            burstAnimator = null
            processingProgress = 1f
            pulseAlpha = 0f
            orbitAlpha = 1f
            invalidate()
            return
        }

        processingAnimator?.cancel()
        processingAnimator = ValueAnimator.ofFloat(processingProgress, 1f).apply {
            duration = PROCESSING_GROW_MS
            interpolator = enterInterpolator
            addUpdateListener { animator ->
                processingProgress = animator.animatedValue as Float
                invalidate()
            }
            start()
        }

        // The payment has been read, so the pulses stop asking for a tap. The glow stays.
        pulseFadeAnimator?.cancel()
        pulseFadeAnimator = ValueAnimator.ofFloat(pulseAlpha, 0f).apply {
            duration = PULSE_FADE_OUT_MS
            addUpdateListener { animator ->
                pulseAlpha = animator.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (pulseAlpha <= 0f) {
                        burstAnimator?.cancel()
                        burstAnimator = null
                    }
                }
            })
            start()
        }

        startOrbitRim(fadeDelayMs = ORBIT_FADE_IN_DELAY_MS)
    }

    /**
     * Enters processing immediately with the disc already filled and grown into its ring,
     * with zero waiting pulses. Used when Stripe's tap screen hands off confirmation to Numo,
     * or when an NFC token lands without an active waiting overlay.
     */
    fun startProcessingDirectly(animateIn: Boolean = true) {
        cancelAnimations()

        val animate = context.isAnimationEnabled()
        currentState = State.PROCESSING
        resultType = null
        currentBackgroundColor = colorSurface
        burstPhase = 0f
        pulseAlpha = 0f
        processingProgress = 1f
        orbitPhase = 0f
        orbitAlpha = if (animate && animateIn) 0f else 1f
        waitAlpha = 1f
        morph = 0f
        grownAtResult = 0f
        floodProgress = 0f
        floodStartFromCenter = false
        resultIconProgress = 0f
        enterProgress = 1f
        activeEnterScale = ENTER_SCALE

        if (animate) {
            if (animateIn) {
                startEnterSettle(ENTER_SCALE, ENTER_MS)
            }
            startOrbitRim(fadeDelayMs = 0L)
        }

        invalidate()
    }

    fun showSuccess(_amountText: String) {
        startResultTransition(ResultType.SUCCESS, direct = false)
    }

    fun showSuccessDirect(_amountText: String) {
        startResultTransition(ResultType.SUCCESS, direct = true)
    }

    fun showError(_message: String) {
        startResultTransition(ResultType.ERROR, direct = false)
    }

    fun showErrorDirect(_message: String) {
        startResultTransition(ResultType.ERROR, direct = true)
    }

    fun reset() {
        cancelAnimations()
        currentState = State.IDLE
        resultType = null
        currentBackgroundColor = Color.TRANSPARENT
        burstPhase = 0f
        pulseAlpha = 0f
        processingProgress = 0f
        orbitPhase = 0f
        orbitAlpha = 0f
        waitAlpha = 0f
        morph = 0f
        grownAtResult = 0f
        floodProgress = 0f
        floodStartFromCenter = false
        resultIconProgress = 0f
        enterProgress = 1f
        activeEnterScale = ENTER_SCALE
        invalidate()
    }

    private fun startEnterSettle(startScale: Float, durationMs: Long) {
        activeEnterScale = startScale
        enterProgress = 0f
        enterAnimator?.cancel()
        enterAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = enterInterpolator
            addUpdateListener { animator ->
                enterProgress = animator.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun startOrbitRim(fadeDelayMs: Long) {
        orbitAnimator?.cancel()
        orbitAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ORBIT_PERIOD_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                orbitPhase = animator.animatedValue as Float
                invalidate()
            }
            start()
        }

        orbitFadeAnimator?.cancel()
        if (orbitAlpha >= 1f) return
        orbitFadeAnimator = ValueAnimator.ofFloat(orbitAlpha, 1f).apply {
            duration = ORBIT_FADE_IN_MS
            startDelay = fadeDelayMs
            interpolator = enterInterpolator
            addUpdateListener { animator ->
                orbitAlpha = animator.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun startResultTransition(target: ResultType, direct: Boolean) {
        if (resultType != null) {
            return
        }

        // Entering result from IDLE or SURFACE_COVER is always direct so QR/Lightning/Arkoor
        // completions never flash the contactless waiting disc or pulses.
        val isDirect = direct || currentState == State.IDLE || currentState == State.SURFACE_COVER
        val targetColor = when (target) {
            ResultType.SUCCESS -> colorSuccess
            ResultType.ERROR -> colorError
        }

        cancelAnimations()
        resultType = target
        floodPaint.color = targetColor

        if (!context.isAnimationEnabled()) {
            currentState = State.RESULT
            currentBackgroundColor = targetColor
            waitAlpha = 0f
            pulseAlpha = 0f
            orbitAlpha = 0f
            morph = 1f
            grownAtResult = 1f
            floodProgress = 0f
            floodStartFromCenter = false
            resultIconProgress = 1f
            enterProgress = 1f
            invalidate()
            onResultDisplayedListener?.invoke(target == ResultType.SUCCESS)
            return
        }

        currentState = State.RESULT_TRANSITION
        currentBackgroundColor = colorSurface
        floodStartFromCenter = isDirect

        if (isDirect) {
            waitAlpha = 0f
            pulseAlpha = 0f
            orbitAlpha = 0f
            morph = 1f
            grownAtResult = 1f
            activeEnterScale = DIRECT_ENTER_SCALE
            enterProgress = 0f
        } else {
            // The disc carries on from wherever it had grown to, so a quick payment does not stall it.
            grownAtResult = processingProgress
            activeEnterScale = ENTER_SCALE
            enterProgress = 1f
        }

        val animators = mutableListOf<Animator>()

        // The result belongs to the disc, so its colour starts there and spreads outwards.
        animators += ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (isDirect) DIRECT_FLOOD_MS else FLOOD_MS
            interpolator = enterInterpolator
            addUpdateListener { animator ->
                floodProgress = animator.animatedValue as Float
                invalidate()
            }
        }

        if (isDirect) {
            animators += ValueAnimator.ofFloat(0f, 1f).apply {
                duration = DIRECT_CIRCLE_ENTER_MS
                interpolator = enterInterpolator
                addUpdateListener { animator ->
                    enterProgress = animator.animatedValue as Float
                    invalidate()
                }
            }
        } else {
            // Waiting layers (waves, glow, shadow, orbiting rim) finish fading out BEFORE the
            // result icon begins drawing, so waves and checkmark never overlap inside the disc.
            animators += ValueAnimator.ofFloat(waitAlpha, 0f).apply {
                duration = WAIT_FADE_OUT_MS
                interpolator = DecelerateInterpolator()
                addUpdateListener { animator ->
                    waitAlpha = animator.animatedValue as Float
                    invalidate()
                }
            }
            animators += ValueAnimator.ofFloat(0f, 1f).apply {
                duration = MORPH_MS
                interpolator = DecelerateInterpolator(1.6f)
                addUpdateListener { animator ->
                    morph = animator.animatedValue as Float
                    invalidate()
                }
            }
        }

        animators += ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (isDirect) DIRECT_ICON_DRAW_MS else RESULT_ICON_DRAW_MS
            startDelay = if (isDirect) DIRECT_ICON_DELAY_MS else RESULT_ICON_DELAY_MS
            interpolator = iconDrawInterpolator
            addUpdateListener { animator ->
                resultIconProgress = animator.animatedValue as Float
                invalidate()
            }
        }

        // The result's buttons arrive while the check is still drawing, not after it.
        var cancelled = false
        animators += ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (isDirect) DIRECT_ACTIONS_CUE_MS else RESULT_ACTIONS_CUE_MS
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    onResultDisplayedListener?.invoke(target == ResultType.SUCCESS)
                }
            })
        }

        transitionAnimator = AnimatorSet().apply {
            playTogether(animators)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    currentState = State.RESULT
                    currentBackgroundColor = targetColor
                    waitAlpha = 0f
                    orbitAlpha = 0f
                    floodProgress = 0f
                    enterProgress = 1f
                    invalidate()
                }
            })
            start()
        }
    }

    private fun cancelAnimations() {
        burstAnimator?.cancel()
        enterAnimator?.cancel()
        pulseFadeAnimator?.cancel()
        processingAnimator?.cancel()
        orbitAnimator?.cancel()
        orbitFadeAnimator?.cancel()
        transitionAnimator?.cancel()
        burstAnimator = null
        enterAnimator = null
        pulseFadeAnimator = null
        processingAnimator = null
        orbitAnimator = null
        orbitFadeAnimator = null
        transitionAnimator = null
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h * CENTER_Y

        discRadius = min(w, h) * DISC_RADIUS
        floodMaxRadius = hypot(max(centerX, w - centerX), max(centerY, h - centerY))

        wavePaint.strokeWidth = discRadius * WAVE_STROKE
        resultIconPaint.strokeWidth = discRadius * RESULT_ICON_STROKE
        resultIconPaint.color = colorSuccess

        val orbitStroke = discRadius * ORBIT_STROKE
        orbitTrackPaint.strokeWidth = orbitStroke
        orbitArcPaint.strokeWidth = orbitStroke
        val orbitRadius = discRadius * ORBIT_RADIUS_FACTOR
        orbitBounds.set(
            centerX - orbitRadius,
            centerY - orbitRadius,
            centerX + orbitRadius,
            centerY + orbitRadius,
        )

        // A steady glow behind the pulses, strongest at the centre and gone at its edge.
        glowPaint.shader = RadialGradient(
            centerX,
            centerY,
            discRadius * GLOW_RADIUS,
            ColorUtils.setAlphaComponent(colorForeground, alphaOf(GLOW_ALPHA)),
            ColorUtils.setAlphaComponent(colorForeground, 0),
            Shader.TileMode.CLAMP,
        )

        rebuildShadow()
        rebuildCheckPath()
        onGeometryChangedListener?.invoke()
    }

    /** Blurs a disc the size of the ring once; drawing scales it to the disc's current size. */
    private fun rebuildShadow() {
        shadowBitmap?.recycle()
        shadowBitmap = null
        if (discRadius <= 0f) return

        val blur = discRadius * DISC_SHADOW_BLUR
        val size = ceil((discRadius + blur * 2f) * 2f).toInt()
        val bitmap = createBitmap(size, size, Bitmap.Config.ALPHA_8)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
        }
        Canvas(bitmap).drawCircle(size / 2f, size / 2f, discRadius, paint)
        shadowBitmap = bitmap
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
        if (currentState == State.IDLE) {
            return
        }

        backgroundPaint.color = currentBackgroundColor
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        if (currentState == State.SURFACE_COVER) {
            return
        }

        val active = currentState == State.PROCESSING || processingProgress > 0f

        if (waitAlpha > 0f) {
            glowPaint.alpha = alphaOf(waitAlpha)
            canvas.drawCircle(centerX, centerY, discRadius * GLOW_RADIUS, glowPaint)
            if (pulseAlpha > 0f) drawPulses(canvas)
        }

        if (floodProgress > 0f) {
            val startRadius = if (floodStartFromCenter) 0f else discRadius
            canvas.drawCircle(
                centerX,
                centerY,
                startRadius + (floodMaxRadius - startRadius) * floodProgress,
                floodPaint,
            )
        }

        // The disc, its ring, its orbit rim, and its symbol settle into place together.
        val settle = activeEnterScale + (1f - activeEnterScale) * enterProgress
        canvas.withScale(settle, settle, centerX, centerY) {

            if (waitAlpha > 0f) {
                // The ring is a faint disc; the disc proper covers all but its rim.
                val outlineAlpha = if (active) DISC_OUTLINE_ALPHA_ACTIVE else DISC_OUTLINE_ALPHA
                discOutlinePaint.color = colorForeground
                discOutlinePaint.alpha = alphaOf(waitAlpha * outlineAlpha)
                canvas.drawCircle(centerX, centerY, discRadius, discOutlinePaint)

                if (orbitAlpha > 0f) {
                    drawOrbitRim(canvas)
                }
            }

            // Stripe swaps the idle disc for the filled one at once, then grows it into the ring.
            val waitingFill = if (active) colorDiscActive else colorSurface
            val grownWaiting = if (resultType == null) processingProgress else grownAtResult
            val grown = grownWaiting + (1f - grownWaiting) * morph
            val radius = discRadius * (DISC_INNER + (1f - DISC_INNER) * grown)
            if (waitAlpha > 0f) drawDiscShadow(canvas, radius)
            discPaint.color = argb.evaluate(morph, waitingFill, Color.WHITE) as Int
            canvas.drawCircle(centerX, centerY, radius, discPaint)

            if (waitAlpha > 0f) {
                drawContactlessWaves(canvas, active)
            }
        }

        if (currentState == State.RESULT_TRANSITION || currentState == State.RESULT) {
            drawResultIcon(canvas)
        }
    }

    /** Calm, Stripe-like orbiting rim around the filled disc so long confirmations stay alive. */
    private fun drawOrbitRim(canvas: Canvas) {
        val combinedAlpha = waitAlpha * orbitAlpha
        orbitTrackPaint.color = colorForeground
        orbitTrackPaint.alpha = alphaOf(combinedAlpha * ORBIT_TRACK_ALPHA)
        canvas.drawOval(orbitBounds, orbitTrackPaint)

        orbitArcPaint.color = colorForeground
        orbitArcPaint.alpha = alphaOf(combinedAlpha * ORBIT_ARC_ALPHA)
        val wave = sin(orbitPhase * 2.0 * PI).toFloat()
        val sweepAngle = ORBIT_SWEEP_BASE_DEG + ORBIT_SWEEP_VARIATION_DEG * wave
        val startAngle = -90f + 360f * orbitPhase - (ORBIT_SWEEP_VARIATION_DEG * 0.5f) * wave
        canvas.drawArc(orbitBounds, startAngle, sweepAngle, false, orbitArcPaint)
    }

    private fun drawDiscShadow(canvas: Canvas, radius: Float) {
        val bitmap = shadowBitmap ?: return
        val scale = radius / discRadius
        shadowPaint.alpha = alphaOf(waitAlpha * DISC_SHADOW_ALPHA)
        canvas.withTranslation(centerX, centerY + discRadius * DISC_SHADOW_OFFSET) {
            scale(scale, scale)
            drawBitmap(bitmap, -bitmap.width / 2f, -bitmap.height / 2f, shadowPaint)
        }
    }

    /**
     * Pulses spread from behind the disc one after another, slowing as they grow and fading
     * fast, so the screen is brightest near the disc. All of them start over together; a pulse
     * waiting for its turn rests behind the ring.
     */
    private fun drawPulses(canvas: Canvas) {
        val elapsedMs = burstPhase * PULSE_PERIOD_MS
        pulsePaint.color = colorForeground
        for (i in 0 until PULSE_COUNT) {
            val t = max(0f, elapsedMs - i * PULSE_STAGGER_MS)
            val fade = max(0f, 1f - t / PULSE_FADE_MS)
            val alpha = PULSE_ALPHA * fade * fade * fade * fade
            if (alpha <= 0f) continue
            val travelled = 1f - max(0f, 1f - t / PULSE_GROWTH_MS).pow(PULSE_GROWTH_POWER)
            pulsePaint.alpha = alphaOf(waitAlpha * pulseAlpha * alpha)
            canvas.drawCircle(centerX, centerY, discRadius * (1f + PULSE_GROWTH * travelled), pulsePaint)
        }
    }

    /** The contactless symbol's waves, at the size they have inside the card tap symbol. */
    private fun drawContactlessWaves(canvas: Canvas, active: Boolean) {
        wavePaint.color = if (active) colorSymbolActive else colorSymbol
        wavePaint.alpha = alphaOf(waitAlpha)
        val originX = centerX - discRadius * WAVE_ORIGIN_OFFSET
        for (i in 0 until WAVE_COUNT) {
            val waveRadius = discRadius * (WAVE_FIRST_RADIUS + WAVE_SPACING * i)
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

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (shadowBitmap == null) rebuildShadow()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        reset()
        shadowBitmap?.recycle()
        shadowBitmap = null
    }

    companion object {
        // Geometry comes from the layouts Stripe's Tap to Pay SDK ships; timing and curves were
        // measured frame by frame from its running tap screen. Sizes are fractions of the
        // view's width or multiples of the ring's radius.
        private const val CENTER_Y = 0.417f
        private const val DISC_RADIUS = 0.26f

        /** The disc's radius inside the ring while waiting. */
        private const val DISC_INNER = 0.9f
        private const val DISC_OUTLINE_ALPHA = 0.2f
        private const val DISC_OUTLINE_ALPHA_ACTIVE = 0.1f
        private const val DISC_SHADOW_BLUR = 0.09f
        private const val DISC_SHADOW_OFFSET = 0.03f
        private const val DISC_SHADOW_ALPHA = 0.22f
        private const val RESULT_ICON_STROKE = 0.075f

        private const val PROCESSING_GROW_MS = 440L

        private const val GLOW_RADIUS = 2.5f
        private const val GLOW_ALPHA = 0.2f

        private const val PULSE_PERIOD_MS = 2600L
        private const val PULSE_COUNT = 3
        private const val PULSE_STAGGER_MS = 230f
        private const val PULSE_ALPHA = 0.25f
        private const val PULSE_FADE_MS = 2500f

        // A pulse's radius is 1 + GROWTH * (1 - (1 - t / GROWTH_MS) ^ GROWTH_POWER) ring radii.
        private const val PULSE_GROWTH = 4.7825f
        private const val PULSE_GROWTH_MS = 5850f
        private const val PULSE_GROWTH_POWER = 3.2f

        // Subtle orbiting rim around the filled disc while processing / confirming.
        private const val ORBIT_RADIUS_FACTOR = 1.095f
        private const val ORBIT_STROKE = 0.036f
        private const val ORBIT_TRACK_ALPHA = 0.12f
        private const val ORBIT_ARC_ALPHA = 0.80f
        private const val ORBIT_SWEEP_BASE_DEG = 84f
        private const val ORBIT_SWEEP_VARIATION_DEG = 34f
        private const val ORBIT_PERIOD_MS = 1350L
        private const val ORBIT_FADE_IN_MS = 240L
        private const val ORBIT_FADE_IN_DELAY_MS = 120L

        private const val WAVE_COUNT = 4
        private const val WAVE_ORIGIN_OFFSET = 0.217f
        private const val WAVE_FIRST_RADIUS = 0.13f
        private const val WAVE_SPACING = 0.061f
        private const val WAVE_STROKE = 0.041f
        private const val WAVE_HALF_SWEEP = 38.5f

        // Numo's own motion, around the parts that mirror Stripe's screen.
        private const val ENTER_MS = 180L
        private const val ENTER_SCALE = 0.96f
        private const val DIRECT_ENTER_SCALE = 0.86f
        private const val PULSE_FADE_OUT_MS = 260L
        internal const val WAIT_FADE_OUT_MS = 140L
        private const val MORPH_MS = 260L
        private const val FLOOD_MS = 380L
        private const val DIRECT_FLOOD_MS = 340L
        private const val DIRECT_CIRCLE_ENTER_MS = 280L
        internal const val RESULT_ICON_DELAY_MS = 160L
        private const val RESULT_ICON_DRAW_MS = 380L
        private const val DIRECT_ICON_DELAY_MS = 120L
        private const val DIRECT_ICON_DRAW_MS = 360L
        private const val RESULT_ACTIONS_CUE_MS = 260L
        private const val DIRECT_ACTIONS_CUE_MS = 240L
    }
}
