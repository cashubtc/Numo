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
import com.electricdreams.numo.ui.util.isAnimationEnabled
import kotlin.math.cos
import kotlin.math.sin

/**
 * Animated "Zero Fees" illustration.
 * Phase 1: Fee percentages fade in scattered around the screen.
 * Phase 2: Each fee gets slashed with a coral-red line,
 *          then splits apart with particles and fades away.
 * Phase 3: Large "0%" bounces in with a radial glow,
 *          then breathes with a gentle pulse loop.
 */
class ZeroFeesIllustration @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private data class FeeLabel(
        val text: String,
        val baseX: Float,   // 0..1 relative
        val baseY: Float,
        val size: Float,    // text size multiplier
        var alpha: Float = 0f,
        var slashProgress: Float = 0f,  // 0..1 line draw
        var destroyed: Float = 0f       // 0..1 split + fade
    )

    private data class Particle(
        val startX: Float,
        val startY: Float,
        val angle: Float,
        val speed: Float,   // max travel distance in px
        val size: Float
    )

    private val fees = listOf(
        FeeLabel("3%",   0.25f, 0.28f, 1.0f),
        FeeLabel("2.5%", 0.50f, 0.28f, 1.0f),
        FeeLabel("1.5%", 0.75f, 0.28f, 1.0f),
        FeeLabel("2%",   0.35f, 0.52f, 1.0f),
        FeeLabel("2.9%", 0.65f, 0.52f, 1.0f),
    )

    private val feeParticles = HashMap<Int, List<Particle>>()

    private var zeroAlpha = 0f
    private var zeroScale = 0.5f

    private var animStarted = false
    private var animScheduled = false
    private var animatorSet: AnimatorSet? = null
    private var pulseAnimator: ValueAnimator? = null
    private var startRunnable: Runnable? = null

    private val feePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }

    private val slashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val zeroPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
    }

    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return
        val cx = w / 2f
        val cy = h * 0.45f
        val s = w / 380f

        for ((index, fee) in fees.withIndex()) {
            if (fee.alpha < 0.01f) continue

            val textSize = 34f * fee.size * s
            feePaint.textSize = textSize
            slashPaint.strokeWidth = 3.5f * s

            val x = fee.baseX * w
            val y = fee.baseY * h

            val textW = feePaint.measureText(fee.text)
            val fadeAlpha = fee.alpha * (1f - fee.destroyed)

            if (fee.destroyed > 0.01f) {
                // Split apart: top half up-left, bottom half down-right
                val drift = fee.destroyed * 20f * s
                val a = (fadeAlpha * 180).toInt().coerceIn(0, 255)

                // Top half
                canvas.save()
                canvas.clipRect(x - textW, y - textSize * 1.2f, x + textW, y - textSize * 0.1f)
                feePaint.alpha = a
                canvas.translate(-drift * 0.4f, -drift)
                canvas.drawText(fee.text, x, y, feePaint)
                canvas.restore()

                // Bottom half
                canvas.save()
                canvas.clipRect(x - textW, y - textSize * 0.1f, x + textW, y + textSize * 0.5f)
                feePaint.alpha = a
                canvas.translate(drift * 0.4f, drift)
                canvas.drawText(fee.text, x, y, feePaint)
                canvas.restore()
            } else {
                // Normal draw
                feePaint.alpha = (fee.alpha * 180).toInt().coerceIn(0, 255)
                canvas.drawText(fee.text, x, y, feePaint)
            }

            // Slash line across this fee
            if (fee.slashProgress > 0f && fee.destroyed < 0.99f) {
                val slashAlpha = ((1f - fee.destroyed) * 220).toInt()
                slashPaint.alpha = slashAlpha
                val sx = x - textW * 0.6f
                val sy = y + 4f * s
                val ex = x + textW * 0.6f
                val ey = y - textSize * 0.7f

                canvas.drawLine(
                    sx, sy,
                    sx + (ex - sx) * fee.slashProgress,
                    sy + (ey - sy) * fee.slashProgress,
                    slashPaint
                )
            }

            // Particles for this fee
            if (fee.destroyed > 0.01f) {
                feeParticles[index]?.let { parts ->
                    val life = (1f - fee.destroyed).coerceIn(0f, 1f)
                    for (p in parts) {
                        if (life <= 0.01f) continue
                        val dist = fee.destroyed * p.speed
                        val px = p.startX + cos(p.angle) * dist
                        val py = p.startY + sin(p.angle) * dist
                        particlePaint.alpha = (life * 180).toInt().coerceIn(0, 255)
                        canvas.drawCircle(px, py, p.size * s, particlePaint)
                    }
                }
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
        fees.forEach { it.alpha = 0f; it.slashProgress = 0f; it.destroyed = 0f }
        feeParticles.clear()
    }

    private fun scheduleAnimation() {
        if (animScheduled || animStarted) return
        if (width == 0 || height == 0) return
        animScheduled = true
        startRunnable = Runnable { startAnimation() }
        postDelayed(startRunnable!!, 500)
    }

    private fun spawnParticles(feeIndex: Int, x: Float, y: Float, s: Float) {
        val seed = feeIndex * 31 + 7
        val parts = (0 until 5).map { i ->
            val angle = ((seed + i * 73) % 360) * (Math.PI.toFloat() / 180f)
            Particle(
                startX = x,
                startY = y,
                angle = angle,
                speed = (30f + ((seed + i * 37) % 30)) * s,
                size = 1.5f + ((seed + i * 53) % 30) / 10f
            )
        }
        feeParticles[feeIndex] = parts
    }

    private fun startAnimation() {
        if (animStarted) return
        animStarted = true

        if (!context.isAnimationEnabled()) {
            // Skip to final state: show "0%" at full scale
            zeroScale = 1f; zeroAlpha = 1f
            fees.forEach { it.alpha = 0f }
            invalidate()
            return
        }

        val w = width.toFloat()
        val h = height.toFloat()
        val s = w / 380f
        val animators = mutableListOf<android.animation.Animator>()

        // Phase 1: All fees fade in (staggered)
        val fadeIn = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 600
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val p = it.animatedValue as Float
                fees.forEachIndexed { i, fee ->
                    val stagger = i * 0.12f
                    fee.alpha = ((p - stagger) / (1f - stagger)).coerceIn(0f, 1f)
                }
                invalidate()
            }
        }
        animators.add(fadeIn)

        // Phase 2: Each fee gets slashed (tighter 180ms stagger)
        fees.forEachIndexed { i, fee ->
            val baseDelay = 1600L + i * 180L

            // Slash line draws
            val slash = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 200
                startDelay = baseDelay
                interpolator = AccelerateInterpolator()
                addUpdateListener {
                    fee.slashProgress = it.animatedValue as Float
                    invalidate()
                }
            }
            animators.add(slash)

            // Split + fade (with 50ms hold after slash for visual beat)
            val destroy = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 350
                startDelay = baseDelay + 250L
                interpolator = AccelerateInterpolator(1.5f)
                var particlesSpawned = false
                addUpdateListener {
                    if (!particlesSpawned) {
                        particlesSpawned = true
                        spawnParticles(i, fee.baseX * w, fee.baseY * h, s)
                    }
                    fee.destroyed = it.animatedValue as Float
                    invalidate()
                }
            }
            animators.add(destroy)
        }

        // Phase 3: "0%" bounces in after all fees destroyed
        val lastFeeEnd = 1600L + (fees.size - 1) * 180L + 250L + 350L
        val zeroIn = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 600
            startDelay = lastFeeEnd + 300L
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
        animators.add(zeroIn)

        animatorSet = AnimatorSet().apply {
            playTogether(animators)
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
