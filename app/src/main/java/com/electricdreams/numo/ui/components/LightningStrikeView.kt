package com.electricdreams.numo.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import com.electricdreams.numo.R
import com.electricdreams.numo.ui.util.isAnimationEnabled
import kotlin.random.Random

/**
 * Full-screen lightning strike overlay animation with realistic branching bolts.
 * Add to the root layout, call [strike], and it auto-removes on completion.
 */
class LightningStrikeView(context: Context) : View(context) {

    private var boltAlpha = 0f
    private val branches = mutableListOf<BoltBranch>()

    private data class BoltBranch(val path: Path, val depth: Int)

    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.color_lightning_bolt_core)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val innerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.color_lightning_bolt_inner_glow)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val outerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.color_lightning_bolt_outer_glow)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun strike() {
        if (!context.isAnimationEnabled()) {
            // Skip animation, just flash briefly and remove
            postDelayed({ (parent as? ViewGroup)?.removeView(this) }, 100)
            return
        }

        post { generateBoltTree() }

        // Fade in (0–120ms) → hold (120–250ms) → fade out (250–550ms)
        ValueAnimator.ofFloat(0f, 1f, 1f, 0f).apply {
            duration = 550
            addUpdateListener {
                boltAlpha = it.animatedValue as Float
                invalidate()
            }
            start()
        }

        postDelayed({
            (parent as? ViewGroup)?.removeView(this)
        }, 600)
    }

    private fun generateBoltTree() {
        branches.clear()
        val w = width.toFloat()
        val h = height.toFloat()

        // Single main bolt near center
        val startX = w * (0.35f + Random.nextFloat() * 0.3f)
        generateTrunk(startX, h * -0.02f, h * 1.02f, w, 0)
    }

    /**
     * Generate a trunk bolt that descends from startY to endY with guaranteed
     * vertical coverage — each segment advances downward by a fixed Y step,
     * only the X position zig-zags.
     */
    private fun generateTrunk(startX: Float, startY: Float, endY: Float, screenW: Float, depth: Int) {
        if (depth > 2) return

        val path = Path()
        path.moveTo(startX, startY)

        val totalHeight = endY - startY
        val segmentCount = when (depth) {
            0 -> Random.nextInt(16, 22)
            1 -> Random.nextInt(8, 14)
            else -> Random.nextInt(5, 9)
        }
        val stepY = totalHeight / segmentCount
        val maxJitterX = screenW * when (depth) {
            0 -> 0.08f
            1 -> 0.06f
            else -> 0.04f
        }

        var x = startX
        var y = startY

        for (i in 0 until segmentCount) {
            // Guaranteed downward progress with random Y variance
            y += stepY * (0.8f + Random.nextFloat() * 0.4f)

            // Sharp zig-zag on X: alternate direction with randomness
            val direction = if (i % 2 == 0) 1f else -1f
            x += direction * maxJitterX * (0.5f + Random.nextFloat())

            // Keep bolt on screen
            x = x.coerceIn(screenW * 0.05f, screenW * 0.95f)

            path.lineTo(x, y)

            // Spawn sub-branches at sharp angles
            if (depth < 2 && i > 0 && i < segmentCount - 1 && Random.nextFloat() < branchChance(depth)) {
                val branchHeight = totalHeight * (0.15f + Random.nextFloat() * 0.15f)
                val branchStartX = x + (if (Random.nextBoolean()) 1f else -1f) * maxJitterX * 2f
                generateTrunk(x, y, y + branchHeight, screenW, depth + 1)
            }
        }

        branches.add(BoltBranch(path, depth))
    }

    private fun branchChance(depth: Int): Float = when (depth) {
        0 -> 0.20f
        1 -> 0.15f
        else -> 0f
    }

    override fun onDraw(canvas: Canvas) {
        if (boltAlpha <= 0f) return
        val alpha = (boltAlpha * 255 * 0.7f).toInt()

        for (branch in branches) {
            val d = branch.depth

            // Outer aura
            outerGlowPaint.strokeWidth = when (d) { 0 -> 16f; 1 -> 10f; else -> 6f }
            outerGlowPaint.maskFilter = BlurMaskFilter(
                when (d) { 0 -> 24f; 1 -> 14f; else -> 8f },
                BlurMaskFilter.Blur.NORMAL
            )
            outerGlowPaint.alpha = (alpha * 0.25f).toInt()
            canvas.drawPath(branch.path, outerGlowPaint)

            // Inner glow
            innerGlowPaint.strokeWidth = when (d) { 0 -> 8f; 1 -> 5f; else -> 3f }
            innerGlowPaint.maskFilter = BlurMaskFilter(
                when (d) { 0 -> 8f; 1 -> 5f; else -> 3f },
                BlurMaskFilter.Blur.NORMAL
            )
            innerGlowPaint.alpha = (alpha * 0.5f).toInt()
            canvas.drawPath(branch.path, innerGlowPaint)

            // Core bolt
            corePaint.strokeWidth = when (d) { 0 -> 3.5f; 1 -> 1.8f; else -> 0.8f }
            corePaint.alpha = alpha
            canvas.drawPath(branch.path, corePaint)
        }
    }
}
