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
import kotlin.math.sin

/**
 * Elegant rising chip animation for the empty state.
 * 
 * Chips float upward from the bottom (near the button), gently bobbing
 * as they rise. They fade in through the gradient at the bottom
 * and wrap around when they reach the top, creating a continuous flow.
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
    
    // Animation time tracking
    private var animationTime = 0f
    
    // Prevent multiple simultaneous setups
    @Volatile private var isSettingUp = false
    @Volatile private var isRunning = false

    data class ChipItem(
        val emoji: String,
        val name: String,
        val backgroundRes: Int,
        val darkText: Boolean
    )
    
    // Tracks position and wave parameters for each floating chip
    private data class FloatingChip(
        val view: TextView,
        val baseX: Float,           // Center X position
        val phaseOffset: Float,     // Phase offset for sine wave (0 to 2π)
        val bobAmplitude: Float,    // How much it bobs up/down
        val driftAmplitude: Float,  // How much it drifts side to side
        val bobSpeed: Float,        // Speed of bobbing
        val driftSpeed: Float,      // Speed of horizontal drift
        val riseSpeed: Float,       // Speed of upward float
        val startOffset: Float,     // Initial Y offset so chips start staggered
        val containerHeight: Float, // For wrapping calculation
        val chipHeight: Float       // For wrapping calculation
    )

    fun start() {
        // Prevent multiple simultaneous setups
        if (isSettingUp || isRunning) return
        isSettingUp = true
        
        // Stop any existing animation
        animator?.cancel()
        animator = null
        floatingChips.clear()
        animationTime = 0f
        
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
        
        // Create each chip and place in organized positions
        chipData.forEachIndexed { index, chip ->
            val chipView = createChipView(chip)
            container.addView(chipView)
            
            // Wait for chip to be measured
            chipView.post {
                if (!isSettingUp && !isRunning) return@post
                
                val chipWidth = chipView.width.toFloat()
                val chipHeight = chipView.height.toFloat()
                
                // Horizontal positions spread across the container
                // Staggered to avoid vertical alignment
                val xPositions = listOf(0.08f, 0.52f, 0.28f, 0.68f, 0.15f, 0.58f)
                val xPercent = xPositions[index]
                
                val padding = 16f
                val usableWidth = containerWidth - chipWidth - (padding * 2)
                val baseX = padding + (usableWidth * xPercent)
                
                // Each chip starts at a different vertical position (staggered across full height)
                // They'll continuously rise and wrap around
                val startYPercent = index.toFloat() / chipData.size
                val startY = containerHeight * startYPercent
                
                // Each chip gets unique animation parameters
                val phaseOffset = (index * Math.PI.toFloat() * 2f / chipData.size)
                
                // Gentle bob amplitude (8-14 pixels) - subtle wobble while rising
                val bobAmplitude = 8f + (index % 3) * 3f
                
                // Small horizontal drift (8-16 pixels)
                val driftAmplitude = 8f + (index % 2) * 8f
                
                // Vary speeds for organic feel
                val bobSpeed = 0.8f + (index % 2) * 0.3f
                val driftSpeed = 0.4f + (index % 3) * 0.15f
                
                // Rise speed - slow upward float (12-20 pixels per second)
                val riseSpeed = 12f + (index % 3) * 4f
                
                // Stagger start positions so chips begin spread across the container
                val wrapHeight = containerHeight + chipHeight
                val startOffset = (index.toFloat() / chipData.size) * wrapHeight
                
                val floatingChip = FloatingChip(
                    view = chipView,
                    baseX = baseX,
                    phaseOffset = phaseOffset,
                    bobAmplitude = bobAmplitude,
                    driftAmplitude = driftAmplitude,
                    bobSpeed = bobSpeed,
                    driftSpeed = driftSpeed,
                    riseSpeed = riseSpeed,
                    startOffset = startOffset,
                    containerHeight = containerHeight,
                    chipHeight = chipHeight
                )
                
                floatingChips.add(floatingChip)
                
                // Set initial position (staggered vertically)
                chipView.translationX = baseX
                chipView.translationY = startY
                
                // Start animation once all chips are set up
                if (floatingChips.size == chipData.size) {
                    startFloatingAnimation()
                }
            }
        }
    }
    
    private fun startFloatingAnimation() {
        // Setup complete, now running
        isSettingUp = false
        isRunning = true
        
        animator?.cancel()
        
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 16L  // ~60fps
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            
            addUpdateListener {
                animationTime += 0.016f  // Approximate delta time in seconds
                updateChipPositions()
            }
        }
        
        animator?.start()
    }
    
    private fun updateChipPositions() {
        floatingChips.forEach { chip ->
            // Calculate smooth sine wave offsets for gentle wobble
            val bobOffset = sin((animationTime * chip.bobSpeed + chip.phaseOffset).toDouble()).toFloat() * chip.bobAmplitude
            val driftOffset = sin((animationTime * chip.driftSpeed + chip.phaseOffset + Math.PI.toFloat() / 2f).toDouble()).toFloat() * chip.driftAmplitude
            
            // Calculate upward movement - chips rise continuously
            // When they go off the top, they wrap to the bottom (coming through gradient)
            val wrapHeight = chip.containerHeight + chip.chipHeight
            val totalRise = animationTime * chip.riseSpeed + chip.startOffset
            
            // Base Y position that rises and wraps
            // Modulo keeps the position cycling, subtract from containerHeight so chips rise upward
            val rawY = chip.containerHeight - (totalRise % wrapHeight)
            
            // Apply positions
            chip.view.translationX = chip.baseX + driftOffset
            chip.view.translationY = rawY + bobOffset
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
            textSize = 18f
            typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
            setTextColor(if (chip.darkText) Color.BLACK else Color.WHITE)
            setBackgroundResource(chip.backgroundRes)
            
            // Padding for the pill shape
            val hPadding = (22 * density).toInt()
            val vPadding = (14 * density).toInt()
            setPadding(hPadding, vPadding, hPadding, vPadding)
            
            gravity = Gravity.CENTER
            maxLines = 1
            isSingleLine = true
            
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            
            // No shadow - clean flat look
        }
    }
}
