package com.electricdreams.numo.ui.animation

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
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
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.withScale
import com.electricdreams.numo.R
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the result moment of the NFC payment overlay, centred on an anchor view
 * (the slot that holds the loading spinner) so the result grows out of the loader:
 *
 * - Success: a full-screen green reveal growing from the anchor, then a white badge
 *   with a green checkmark drawn in.
 * - Error: a red badge with a white ✕ drawn in, over the app background.
 *
 * The loading spinner is a Material progress indicator owned by the layout. With system
 * animations removed, results jump straight to their final frame.
 */
class NfcPaymentAnimationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private enum class State { IDLE, TRANSITION, RESULT }

    private enum class ResultType { SUCCESS, ERROR }

    private val colorSuccess = ContextCompat.getColor(context, R.color.color_nfc_success)
    private val colorError = ContextCompat.getColor(context, R.color.color_error)

    private val revealPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = colorSuccess
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = resources.getDimension(R.dimen.nfc_result_icon_stroke_width)
    }

    private var state = State.IDLE
    private var resultType: ResultType? = null

    private var revealFraction = 0f
    private var badgeScale = 0f
    private var badgeAlpha = 0f
    private var glyphProgress = 0f

    private var anchor: View? = null
    private val anchorLocation = IntArray(2)
    private val ownLocation = IntArray(2)
    private var centerX = 0f
    private var centerY = 0f
    private var badgeRadius = resources.getDimension(R.dimen.nfc_indicator_size) / 2f
    private var maxRevealRadius = 0f

    private val checkPath = Path()
    private val checkPathMeasure = PathMeasure()
    private val checkPathSegment = Path()
    private var checkPathLength = 0f

    private var transitionAnimator: AnimatorSet? = null
    private var onResultDisplayedListener: ((Boolean) -> Unit)? = null

    // Keeps the badge on the anchor when the content around it re-lays out (e.g. the
    // error reason appearing below, or a large font scale making the column scroll).
    private val anchorTracker = ViewTreeObserver.OnPreDrawListener {
        if (resultType != null) {
            val previousX = centerX
            val previousY = centerY
            updateGeometry()
            if (previousX != centerX || previousY != centerY) invalidate()
        }
        true
    }

    fun setOnResultDisplayedListener(listener: ((Boolean) -> Unit)?) {
        onResultDisplayedListener = listener
    }

    /** The view the result badge and success reveal are centred on. */
    fun setAnchor(view: View) {
        anchor = view
    }

    /** Reveals the success state; the full-screen green grows from the anchor. */
    fun showSuccess() {
        startResultTransition(ResultType.SUCCESS)
    }

    /** Shows the error badge over the app background; there is no full-screen colour. */
    fun showError() {
        startResultTransition(ResultType.ERROR)
    }

    fun reset() {
        cancelAnimations()
        state = State.IDLE
        resultType = null
        revealFraction = 0f
        badgeScale = 0f
        badgeAlpha = 0f
        glyphProgress = 0f
        invalidate()
    }

    private fun startResultTransition(target: ResultType) {
        if (resultType != null) return

        resultType = target
        state = State.TRANSITION
        updateGeometry()

        if (ReducedMotion.isEnabled(context)) {
            revealFraction = if (target == ResultType.SUCCESS) 1f else 0f
            badgeScale = 1f
            badgeAlpha = 1f
            glyphProgress = 1f
            state = State.RESULT
            invalidate()
            post { onResultDisplayedListener?.invoke(target == ResultType.SUCCESS) }
            return
        }

        val animators = mutableListOf<Animator>()
        val badgeDelay: Long
        val glyphDelay: Long
        if (target == ResultType.SUCCESS) {
            animators += floatAnimator(REVEAL_DURATION_MS, 0L, EMPHASIZED_DECELERATE) {
                revealFraction = it
            }
            badgeDelay = SUCCESS_BADGE_DELAY_MS
            glyphDelay = SUCCESS_GLYPH_DELAY_MS
        } else {
            badgeDelay = ERROR_BADGE_DELAY_MS
            glyphDelay = ERROR_GLYPH_DELAY_MS
        }
        animators += floatAnimator(BADGE_DURATION_MS, badgeDelay, EMPHASIZED_DECELERATE) {
            badgeScale = BADGE_START_SCALE + (1f - BADGE_START_SCALE) * it
        }
        animators += floatAnimator(BADGE_FADE_DURATION_MS, badgeDelay, STANDARD) {
            badgeAlpha = it
        }
        animators += floatAnimator(GLYPH_DURATION_MS, glyphDelay, STANDARD) {
            glyphProgress = it
        }

        transitionAnimator = AnimatorSet().apply {
            playTogether(animators)
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    state = State.RESULT
                    onResultDisplayedListener?.invoke(resultType == ResultType.SUCCESS)
                }
            })
            start()
        }
    }

    private fun floatAnimator(
        duration: Long,
        delay: Long,
        interpolator: PathInterpolator,
        onUpdate: (Float) -> Unit,
    ): ValueAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        this.duration = duration
        startDelay = delay
        this.interpolator = interpolator
        addUpdateListener { animator ->
            onUpdate(animator.animatedValue as Float)
            invalidate()
        }
    }

    private fun cancelAnimations() {
        transitionAnimator?.cancel()
        transitionAnimator = null
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateGeometry()
    }

    /** Recomputes the badge centre from the anchor's on-screen position. */
    private fun updateGeometry() {
        val anchorView = anchor
        if (anchorView != null && anchorView.width > 0) {
            anchorView.getLocationInWindow(anchorLocation)
            getLocationInWindow(ownLocation)
            centerX = (anchorLocation[0] - ownLocation[0]) + anchorView.width / 2f
            centerY = (anchorLocation[1] - ownLocation[1]) + anchorView.height / 2f
            badgeRadius = min(anchorView.width, anchorView.height) / 2f
        } else {
            centerX = width / 2f
            centerY = height / 2f
        }
        maxRevealRadius = hypot(max(centerX, width - centerX), max(centerY, height - centerY))
        rebuildCheckPath()
    }

    private fun rebuildCheckPath() {
        val size = badgeRadius * 0.9f
        checkPath.reset()
        checkPath.moveTo(centerX - size * 0.36f, centerY + size * 0.02f)
        checkPath.lineTo(centerX - size * 0.1f, centerY + size * 0.28f)
        checkPath.lineTo(centerX + size * 0.38f, centerY - size * 0.24f)
        checkPathMeasure.setPath(checkPath, false)
        checkPathLength = checkPathMeasure.length
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val result = resultType ?: return
        if (state == State.IDLE) return

        if (result == ResultType.SUCCESS && revealFraction > 0f) {
            canvas.drawCircle(centerX, centerY, maxRevealRadius * revealFraction, revealPaint)
        }

        if (badgeScale <= 0f) return
        badgePaint.color = if (result == ResultType.SUCCESS) Color.WHITE else colorError
        badgePaint.alpha = (badgeAlpha * 255).toInt().coerceIn(0, 255)
        canvas.drawCircle(centerX, centerY, badgeRadius * badgeScale, badgePaint)

        if (glyphProgress <= 0f) return
        canvas.withScale(badgeScale, badgeScale, centerX, centerY) {
            when (result) {
                ResultType.SUCCESS -> drawCheck(this)
                ResultType.ERROR -> drawCross(this)
            }
        }
    }

    private fun drawCheck(canvas: Canvas) {
        glyphPaint.color = colorSuccess
        checkPathSegment.reset()
        checkPathMeasure.getSegment(0f, checkPathLength * glyphProgress, checkPathSegment, true)
        canvas.drawPath(checkPathSegment, glyphPaint)
    }

    private fun drawCross(canvas: Canvas) {
        glyphPaint.color = Color.WHITE
        val arm = badgeRadius * 0.3f
        val first = min(1f, glyphProgress * 2f)
        val second = min(1f, max(0f, (glyphProgress - 0.5f) * 2f))

        canvas.drawLine(
            centerX - arm,
            centerY - arm,
            centerX - arm + 2 * arm * first,
            centerY - arm + 2 * arm * first,
            glyphPaint,
        )
        if (second > 0f) {
            canvas.drawLine(
                centerX + arm,
                centerY - arm,
                centerX + arm - 2 * arm * second,
                centerY - arm + 2 * arm * second,
                glyphPaint,
            )
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(anchorTracker)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(anchorTracker)
        super.onDetachedFromWindow()
        cancelAnimations()
    }

    companion object {
        private const val REVEAL_DURATION_MS = 520L
        private const val BADGE_DURATION_MS = 380L
        private const val BADGE_FADE_DURATION_MS = 160L
        private const val GLYPH_DURATION_MS = 340L
        private const val SUCCESS_BADGE_DELAY_MS = 140L
        private const val SUCCESS_GLYPH_DELAY_MS = 320L
        private const val ERROR_BADGE_DELAY_MS = 120L
        private const val ERROR_GLYPH_DELAY_MS = 280L
        private const val BADGE_START_SCALE = 0.6f

        // Material 3 motion curves
        private val EMPHASIZED_DECELERATE = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
        private val STANDARD = PathInterpolator(0.2f, 0f, 0f, 1f)
    }
}
