package com.electricdreams.numo.feature.items

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.electricdreams.numo.R
import kotlin.math.sin

/**
 * Elegant floating tile animation for the empty state.
 * 
 * Square tiles with rounded corners pop in from the bottom with a bouncy
 * entrance, then float upward with gentle movement. They fade in through
 * the gradient at the bottom and wrap around when they reach the top.
 */
class EmptyStateAnimator(
    private val context: Context,
    private val ribbonContainer: View
) {
    // Tile configurations with different sizes and colors
    private val tileData = listOf(
        TileItem("👕", R.color.chip_ribbon_cyan, Size.LARGE),
        TileItem("🥩", R.color.chip_ribbon_pink, Size.MEDIUM),
        TileItem("🌿", R.color.chip_ribbon_lime, Size.SMALL),
        TileItem("🥜", R.color.chip_ribbon_green, Size.LARGE),
        TileItem("💵", R.color.chip_ribbon_purple, Size.MEDIUM),
        TileItem("🐮", R.color.chip_ribbon_yellow, Size.SMALL),
        TileItem("☕", R.color.chip_ribbon_orange, Size.MEDIUM),
        TileItem("🎸", R.color.chip_ribbon_cyan, Size.SMALL)
    )

    private var floatAnimator: ValueAnimator? = null
    private val floatingTiles = mutableListOf<FloatingTile>()
    
    // Container for the floating tiles
    private var tileContainer: FrameLayout? = null
    
    // Animation time tracking
    private var animationTime = 0f
    
    // Prevent multiple simultaneous setups
    @Volatile private var isSettingUp = false
    @Volatile private var isAnimationRunning = false
    
    // Pop-in animation complete flag
    private var popInComplete = false

    enum class Size { SMALL, MEDIUM, LARGE }

    data class TileItem(
        val emoji: String,
        val colorRes: Int,
        val size: Size
    )
    
    // Tracks position and animation parameters for each floating tile
    private data class FloatingTile(
        val view: View,
        val baseX: Float,
        val phaseOffset: Float,
        val bobAmplitude: Float,
        val driftAmplitude: Float,
        val bobSpeed: Float,
        val driftSpeed: Float,
        val riseSpeed: Float,
        var startOffset: Float,  // Mutable - set after pop-in completes
        val containerHeight: Float,
        val tileSize: Float,
        val rotationSpeed: Float,
        var baseRotation: Float  // Mutable - set after pop-in completes
    )

    fun start() {
        // Prevent multiple simultaneous setups
        if (isSettingUp || isAnimationRunning) return
        isSettingUp = true
        popInComplete = false
        
        // Stop any existing animation
        floatAnimator?.cancel()
        floatAnimator = null
        floatingTiles.clear()
        animationTime = 0f
        
        // Find or use the ribbon container as our tile container
        tileContainer = ribbonContainer.findViewById<FrameLayout>(R.id.ribbon_rows_wrapper)
            ?: (ribbonContainer as? FrameLayout)
        
        tileContainer?.let { container ->
            // Clear any existing content
            container.removeAllViews()
            
            // Reset transform
            container.rotation = 0f
            container.scaleX = 1f
            container.scaleY = 1f
            container.translationX = 0f
            container.translationY = 0f
            
            // Wait for layout to get container dimensions
            container.post {
                if (isSettingUp) {
                    setupFloatingTiles(container)
                }
            }
        }
    }
    
    private fun setupFloatingTiles(container: FrameLayout) {
        val containerWidth = container.width.toFloat()
        val containerHeight = container.height.toFloat()
        
        if (containerWidth <= 0 || containerHeight <= 0) return
        
        floatingTiles.clear()
        val density = context.resources.displayMetrics.density
        
        // Create each tile
        tileData.forEachIndexed { index, tile ->
            val tileView = createTileView(tile, density)
            container.addView(tileView)
            
            // Get the size for this tile
            val tileSize = when (tile.size) {
                Size.SMALL -> 56 * density
                Size.MEDIUM -> 72 * density
                Size.LARGE -> 88 * density
            }
            
            // Horizontal positions - spread across with some randomness
            val xPositions = listOf(0.05f, 0.55f, 0.25f, 0.70f, 0.12f, 0.60f, 0.38f, 0.82f)
            val xPercent = xPositions[index % xPositions.size]
            
            val padding = 16f
            val usableWidth = containerWidth - tileSize - (padding * 2)
            val baseX = padding + (usableWidth * xPercent)
            
            // Each tile gets unique animation parameters
            val phaseOffset = (index * Math.PI.toFloat() * 2f / tileData.size)
            
            // Gentle bob amplitude varies by size
            val bobAmplitude = when (tile.size) {
                Size.SMALL -> 6f + (index % 3) * 2f
                Size.MEDIUM -> 8f + (index % 3) * 2f
                Size.LARGE -> 10f + (index % 3) * 2f
            }
            
            // Horizontal drift
            val driftAmplitude = 10f + (index % 3) * 5f
            
            // Vary speeds for organic feel
            val bobSpeed = 0.6f + (index % 3) * 0.15f
            val driftSpeed = 0.3f + (index % 4) * 0.1f
            
            // Rise speed - larger tiles rise slower
            val riseSpeed = when (tile.size) {
                Size.SMALL -> 18f + (index % 3) * 3f
                Size.MEDIUM -> 14f + (index % 3) * 2f
                Size.LARGE -> 10f + (index % 3) * 2f
            }
            
            // Subtle rotation speed
            val rotationSpeed = 0.3f + (index % 3) * 0.1f
            
            // startOffset will be calculated after pop-in animation completes
            // to ensure seamless transition from pop-in to floating
            val startOffset = 0f
            
            val floatingTile = FloatingTile(
                view = tileView,
                baseX = baseX,
                phaseOffset = phaseOffset,
                bobAmplitude = bobAmplitude,
                driftAmplitude = driftAmplitude,
                bobSpeed = bobSpeed,
                driftSpeed = driftSpeed,
                riseSpeed = riseSpeed,
                startOffset = startOffset,
                containerHeight = containerHeight,
                tileSize = tileSize,
                rotationSpeed = rotationSpeed,
                baseRotation = 0f  // Will be set after pop-in completes
            )
            
            floatingTiles.add(floatingTile)
            
            // Set initial position (off-screen at bottom)
            tileView.translationX = baseX
            tileView.translationY = containerHeight + 100f
            tileView.scaleX = 0f
            tileView.scaleY = 0f
            tileView.alpha = 0f
        }
        
        // Start pop-in animations with stagger
        startPopInAnimations()
    }
    
    private fun startPopInAnimations() {
        val containerHeight = tileContainer?.height?.toFloat() ?: return
        var completedCount = 0
        
        floatingTiles.forEachIndexed { index, tile ->
            val delay = index * 80L // Stagger each tile by 80ms
            
            // Calculate target Y position (staggered across height)
            val startYPercent = index.toFloat() / floatingTiles.size
            val targetY = containerHeight * startYPercent
            
            // Create pop-in animation set
            val scaleXAnim = ObjectAnimator.ofFloat(tile.view, "scaleX", 0f, 1.1f, 1f)
            val scaleYAnim = ObjectAnimator.ofFloat(tile.view, "scaleY", 0f, 1.1f, 1f)
            val alphaAnim = ObjectAnimator.ofFloat(tile.view, "alpha", 0f, 1f)
            val translateYAnim = ObjectAnimator.ofFloat(
                tile.view, "translationY", 
                containerHeight + 100f, 
                targetY - 20f,
                targetY
            )
            
            // Small rotation during entrance
            val initialRotation = -8f + (index % 3) * 4f
            val rotationAnim = ObjectAnimator.ofFloat(tile.view, "rotation", 0f, initialRotation)
            
            val animatorSet = AnimatorSet()
            animatorSet.playTogether(scaleXAnim, scaleYAnim, alphaAnim, translateYAnim, rotationAnim)
            animatorSet.duration = 500L
            animatorSet.startDelay = delay
            animatorSet.interpolator = OvershootInterpolator(0.8f)
            
            animatorSet.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // Calculate startOffset so floating animation continues from current position
                    // The floating formula is: rawY = containerHeight - (totalRise % wrapHeight)
                    // At time 0: rawY = containerHeight - startOffset (when startOffset < wrapHeight)
                    // We want rawY = targetY, so: startOffset = containerHeight - targetY
                    val wrapHeight = tile.containerHeight + tile.tileSize
                    tile.startOffset = (tile.containerHeight - targetY) % wrapHeight
                    
                    // Store the final rotation so floating animation continues from it
                    tile.baseRotation = initialRotation
                    
                    completedCount++
                    // Start floating only after ALL tiles have finished pop-in
                    if (completedCount == floatingTiles.size) {
                        popInComplete = true
                        isSettingUp = false
                        isAnimationRunning = true
                        startFloatingAnimation()
                    }
                }
            })
            
            animatorSet.start()
        }
    }
    
    private fun startFloatingAnimation() {
        floatAnimator?.cancel()
        
        floatAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 16L  // ~60fps
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            
            addUpdateListener {
                animationTime += 0.016f
                updateTilePositions()
            }
        }
        
        floatAnimator?.start()
    }
    
    private fun updateTilePositions() {
        floatingTiles.forEach { tile ->
            // Smoothly ramp in bob, drift, and rotation over the first few frames
            // so that the tiles continue seamlessly from their pop-in positions.
            val introDurationSeconds = 0.5f
            val introFactor = kotlin.math.min(1f, animationTime / introDurationSeconds)

            // Calculate smooth sine wave offsets for gentle movement
            val bobOffset = sin(
                (animationTime * tile.bobSpeed + tile.phaseOffset).toDouble()
            ).toFloat() * tile.bobAmplitude * introFactor

            val driftOffset = sin(
                (animationTime * tile.driftSpeed + tile.phaseOffset + Math.PI.toFloat() / 2f)
                    .toDouble()
            ).toFloat() * tile.driftAmplitude * introFactor

            // Calculate upward movement with wrap-around
            val wrapHeight = tile.containerHeight + tile.tileSize
            val totalRise = animationTime * tile.riseSpeed + tile.startOffset

            // Base Y position that rises and wraps
            val rawY = tile.containerHeight - (totalRise % wrapHeight)

            // Subtle rotation oscillation added to the base rotation from pop-in
            val rotationOscillation = sin(
                (animationTime * tile.rotationSpeed + tile.phaseOffset).toDouble()
            ).toFloat() * 2f * introFactor

            // Apply positions
            tile.view.translationX = tile.baseX + driftOffset
            tile.view.translationY = rawY + bobOffset
            tile.view.rotation = tile.baseRotation + rotationOscillation
        }
    }


    fun stop() {
        isSettingUp = false
        isAnimationRunning = false
        popInComplete = false
        floatAnimator?.cancel()
        floatAnimator = null
        floatingTiles.clear()
        tileContainer?.removeAllViews()
    }

    private fun createTileView(tile: TileItem, density: Float): View {
        // Get size in pixels
        val sizePx = when (tile.size) {
            Size.SMALL -> (56 * density).toInt()
            Size.MEDIUM -> (72 * density).toInt()
            Size.LARGE -> (88 * density).toInt()
        }
        
        // Corner radius scales with size
        val radiusPx = when (tile.size) {
            Size.SMALL -> 12 * density
            Size.MEDIUM -> 16 * density
            Size.LARGE -> 20 * density
        }
        
        // Emoji size scales with tile size
        val emojiSize = when (tile.size) {
            Size.SMALL -> 24f
            Size.MEDIUM -> 32f
            Size.LARGE -> 40f
        }
        
        return TextView(context).apply {
            text = tile.emoji
            textSize = emojiSize
            gravity = Gravity.CENTER
            
            // Create rounded square background
            val bgDrawable = GradientDrawable()
            bgDrawable.shape = GradientDrawable.RECTANGLE
            bgDrawable.setColor(ContextCompat.getColor(context, tile.colorRes))
            bgDrawable.cornerRadius = radiusPx
            background = bgDrawable
            
            // Set fixed size
            layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
            
            // Add subtle elevation for depth
            elevation = 4 * density
        }
    }
}
