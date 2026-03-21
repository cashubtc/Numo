package com.electricdreams.numo.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import kotlin.random.Random

/**
 * Full-screen lightning strike overlay animation.
 * Add to the root layout, call [strike], and it auto-removes on completion.
 */
class LightningStrikeView(context: Context) : View(context) {

    private var boltAlpha = 0f

    private val mainBolt = Path()
    private val forkBolt = Path()

    private val boltPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F7931A")
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFBB5C")
        style = Paint.Style.STROKE
        strokeWidth = 14f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        maskFilter = BlurMaskFilter(24f, BlurMaskFilter.Blur.NORMAL)
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun strike() {
        post { generateBoltPaths() }

        // Bolt appears and fades out (0–400ms)
        ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 400
            interpolator = AccelerateInterpolator(2f)
            addUpdateListener {
                boltAlpha = it.animatedValue as Float
                invalidate()
            }
            start()
        }

        // Auto-remove after animation completes
        postDelayed({
            (parent as? ViewGroup)?.removeView(this)
        }, 450)
    }

    private fun generateBoltPaths() {
        val w = width.toFloat()
        val h = height.toFloat()

        // Main bolt: starts near top-center, jagged path downward
        mainBolt.reset()
        var x = w * 0.45f + Random.nextFloat() * w * 0.1f
        var y = h * 0.05f
        mainBolt.moveTo(x, y)

        val segments = 8
        val segmentHeight = h * 0.7f / segments
        var forkStartX = 0f
        var forkStartY = 0f

        for (i in 0 until segments) {
            val offsetX = (Random.nextFloat() - 0.5f) * w * 0.12f
            x += offsetX
            y += segmentHeight + (Random.nextFloat() - 0.5f) * segmentHeight * 0.3f
            mainBolt.lineTo(x, y)
            if (i == 3) {
                forkStartX = x
                forkStartY = y
            }
        }

        // Fork: branches off from segment 3
        forkBolt.reset()
        forkBolt.moveTo(forkStartX, forkStartY)
        var fx = forkStartX
        var fy = forkStartY
        for (i in 0 until 4) {
            fx += w * 0.06f + Random.nextFloat() * w * 0.04f
            fy += segmentHeight * 0.8f + (Random.nextFloat() - 0.5f) * segmentHeight * 0.3f
            forkBolt.lineTo(fx, fy)
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (boltAlpha > 0f) {
            val alpha = (boltAlpha * 255).toInt()

            glowPaint.alpha = (alpha * 0.6f).toInt()
            canvas.drawPath(mainBolt, glowPaint)
            canvas.drawPath(forkBolt, glowPaint)

            boltPaint.alpha = alpha
            canvas.drawPath(mainBolt, boltPaint)
            canvas.drawPath(forkBolt, boltPaint)
        }
    }
}
