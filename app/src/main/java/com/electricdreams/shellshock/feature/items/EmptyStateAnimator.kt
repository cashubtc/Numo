package com.electricdreams.shellshock.feature.items

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import com.electricdreams.shellshock.R
import kotlin.math.abs
import kotlin.random.Random

/**
 * Elegant floating chip animation for the empty state.
 * 
 * Each chip gently floats around the container, bouncing softly off edges.
 * Much simpler than scrolling - all 6 chips are created upfront and just move.
 * No rendering timing issues since chips don't enter/exit the viewport.
 */
class EmptyStateAnimator(
    private val context: Context,
    private val ribbonContainer: View
) {
    // 6 visually distinct chips
    private val chipData = listOf(
        ChipItem("👕", "SHIRTS", R.drawable.bg_chip_ribbon_cyan, true),
        ChipItem("🥩", "STEAKS", R.drawable.bg_chip_ribbon_pink, true),
        ChipItem("🌿", "PLANTS", R.drawable.bg_chip_ribbon_lime, true),
        ChipItem("🥜", "PEANUTS", R.drawable.bg_chip_ribbon_green, false),
        ChipItem("💵", "FIAT", R.drawable.bg_chip_ribbon_purple, false),
        ChipItem("🐮", "TALLOW", R.drawable.bg_chip_ribbon_yellow, true)
    )

    private var animator: ValueAnimator? = null
    private val floatingChips = mutableListOf<FloatingChip>()
    
    // Container for the floating chips
    private var chipContainer: FrameLayout? = null
    
    // Prevent multiple simultaneous setups
    @Volatile private var isSettingUp = false
    @Volatile private var isRunning = false

    data class ChipItem(
        val emoji: String,
        val name: String,
        val backgroundRes: Int,
        val darkText: Boolean
    )
    
    // Tracks position and velocity for each floating chip
    private data class FloatingChip(
        val view: TextView,
        var x: Float,
        var y: Float,
        var dx: Float,  // velocity X
        var dy: Float,  // velocity Y
        var rotation: Float,
        var dRotation: Float  // rotation speed
    )

    fun start() {
        // Prevent multiple simultaneous setups
        if (isSettingUp || isRunning) return
        isSettingUp = true
        
        // Stop any existing animation
        animator?.cancel()
        animator = null
        floatingChips.clear()
        
        // Find or use the ribbon container as our chip container
        chipContainer = ribbonContainer.findViewById<FrameLayout>(R.id.ribbon_rows_wrapper)
            ?: (ribbonContainer as? FrameLayout)
        
        chipContainer?.let { container ->
            // Clear any existing content
            container.removeAllViews()
            
            // Reset transform from previous ribbon animation
            container.rotation = 0f
            container.scaleX = 1f
            container.scaleY = 1f
            container.translationX = 0f
            container.translationY = 0f
            
            // Wait for layout to get container dimensions
            container.post {
                if (isSettingUp) {
                    setupFloatingChips(container)
                }
            }
        }
    }
    
    private fun setupFloatingChips(container: FrameLayout) {
        val containerWidth = container.width.toFloat()
        val containerHeight = container.height.toFloat()
        
        if (containerWidth <= 0 || containerHeight <= 0) return
        
        floatingChips.clear()
        
        // Create each chip with a random starting position and velocity
        chipData.forEachIndexed { index, chip ->
            val chipView = createChipView(chip)
            container.addView(chipView)
            
            // Wait for chip to be measured
            chipView.post {
                if (!isSettingUp && !isRunning) return@post
                
                val chipWidth = chipView.width.toFloat()
                val chipHeight = chipView.height.toFloat()
                
                // Fully random starting position anywhere in the container
                val maxX = (containerWidth - chipWidth).coerceAtLeast(0f)
                val maxY = (containerHeight - chipHeight).coerceAtLeast(0f)
                
                val startX = Random.nextFloat() * maxX
                val startY = Random.nextFloat() * maxY
                
                // Random velocity - lively movement in any direction
                val speed = 1.2f + Random.nextFloat() * 1.0f  // 1.2 to 2.2 pixels per frame
                val angle = Random.nextFloat() * 360f
                val dx = speed * kotlin.math.cos(Math.toRadians(angle.toDouble())).toFloat()
                val dy = speed * kotlin.math.sin(Math.toRadians(angle.toDouble())).toFloat()
                
                // Random rotation for visual interest
                val rotation = Random.nextFloat() * 20f - 10f  // -10 to 10 degrees
                val dRotation = (Random.nextFloat() - 0.5f) * 0.2f  // Rotation speed
                
                val floatingChip = FloatingChip(
                    view = chipView,
                    x = startX,
                    y = startY,
                    dx = dx,
                    dy = dy,
                    rotation = rotation,
                    dRotation = dRotation
                )
                
                floatingChips.add(floatingChip)
                
                // Set initial position
                chipView.translationX = startX
                chipView.translationY = startY
                chipView.rotation = rotation
                
                // Start animation once all chips are set up
                if (floatingChips.size == chipData.size) {
                    startFloatingAnimation(containerWidth, containerHeight)
                }
            }
        }
    }
    
    private fun startFloatingAnimation(containerWidth: Float, containerHeight: Float) {
        // Setup complete, now running
        isSettingUp = false
        isRunning = true
        
        animator?.cancel()
        
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 16L  // ~60fps
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            
            addUpdateListener {
                updateChipPositions(containerWidth, containerHeight)
            }
        }
        
        animator?.start()
    }
    
    private fun updateChipPositions(containerWidth: Float, containerHeight: Float) {
        floatingChips.forEach { chip ->
            val chipWidth = chip.view.width.toFloat()
            val chipHeight = chip.view.height.toFloat()
            
            // Update position
            chip.x += chip.dx
            chip.y += chip.dy
            chip.rotation += chip.dRotation
            
            // Bounce off edges with slight randomness
            val maxX = (containerWidth - chipWidth).coerceAtLeast(0f)
            val maxY = (containerHeight - chipHeight).coerceAtLeast(0f)
            
            if (chip.x <= 0f) {
                chip.x = 0f
                chip.dx = abs(chip.dx) * (0.9f + Random.nextFloat() * 0.2f)
                chip.dRotation = -chip.dRotation
            } else if (chip.x >= maxX) {
                chip.x = maxX
                chip.dx = -abs(chip.dx) * (0.9f + Random.nextFloat() * 0.2f)
                chip.dRotation = -chip.dRotation
            }
            
            if (chip.y <= 0f) {
                chip.y = 0f
                chip.dy = abs(chip.dy) * (0.9f + Random.nextFloat() * 0.2f)
            } else if (chip.y >= maxY) {
                chip.y = maxY
                chip.dy = -abs(chip.dy) * (0.9f + Random.nextFloat() * 0.2f)
            }
            
            // Clamp rotation to reasonable range
            chip.rotation = chip.rotation.coerceIn(-15f, 15f)
            
            // Apply position and rotation
            chip.view.translationX = chip.x
            chip.view.translationY = chip.y
            chip.view.rotation = chip.rotation
        }
    }

    fun stop() {
        isSettingUp = false
        isRunning = false
        animator?.cancel()
        animator = null
        floatingChips.clear()
        chipContainer?.removeAllViews()
    }

    private fun createChipView(chip: ChipItem): TextView {
        val density = context.resources.displayMetrics.density
        
        return TextView(context).apply {
            text = "${chip.emoji}  ${chip.name}"
            textSize = 18f  // Larger text
            typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
            setTextColor(if (chip.darkText) Color.BLACK else Color.WHITE)
            setBackgroundResource(chip.backgroundRes)
            
            // Larger padding for bigger chips
            val hPadding = (22 * density).toInt()
            val vPadding = (14 * density).toInt()
            setPadding(hPadding, vPadding, hPadding, vPadding)
            
            gravity = Gravity.CENTER
            maxLines = 1
            isSingleLine = true
            
            // Use FrameLayout.LayoutParams since we're adding to a FrameLayout
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            
            // Add shadow for depth
            elevation = 6 * density
        }
    }
}
