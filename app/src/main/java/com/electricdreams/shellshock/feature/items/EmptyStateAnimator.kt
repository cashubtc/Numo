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
import android.widget.LinearLayout
import android.widget.TextView
import com.electricdreams.shellshock.R

/**
 * Handles the animated chip ribbon for the empty state.
 * Chips start OFF-SCREEN to the right and scroll left into view.
 * This ensures all chips are fully rendered before becoming visible.
 */
class EmptyStateAnimator(
    private val context: Context,
    private val ribbonContainer: View
) {
    // 6 visually distinct chips
    private val chipData = listOf(
        ChipItem("👕", "SHIRTS", R.drawable.bg_chip_ribbon_cyan, true),
        ChipItem("₿", "BITCOIN", R.drawable.bg_chip_ribbon_pink, true),
        ChipItem("🌿", "PLANTS", R.drawable.bg_chip_ribbon_lime, true),
        ChipItem("🥜", "PEANUTS", R.drawable.bg_chip_ribbon_green, false),
        ChipItem("💵", "FIAT", R.drawable.bg_chip_ribbon_purple, false),
        ChipItem("🐮", "TALLOW", R.drawable.bg_chip_ribbon_yellow, true)
    )

    // FAST animation - 3-4 seconds per cycle (doubled from before)
    private val rowDurations = listOf(3500L, 4000L, 3200L)
    
    // Staggered start positions
    private val rowStartOffsets = listOf(0f, 0.5f, 0.25f)

    private val animators = mutableListOf<ValueAnimator>()
    private var isStarted = false

    data class ChipItem(
        val emoji: String,
        val name: String,
        val backgroundRes: Int,
        val darkText: Boolean
    )

    fun start() {
        if (isStarted) return
        isStarted = true
        stop()
        
        val row1 = ribbonContainer.findViewById<LinearLayout>(R.id.ribbon_row_1)
        val row2 = ribbonContainer.findViewById<LinearLayout>(R.id.ribbon_row_2)
        val row3 = ribbonContainer.findViewById<LinearLayout>(R.id.ribbon_row_3)
        val rowsWrapper = ribbonContainer.findViewById<FrameLayout>(R.id.ribbon_rows_wrapper)

        val rowContainers = listOf(row1, row2, row3)

        // Apply rotation after layout
        rowsWrapper?.post {
            rowsWrapper.pivotX = rowsWrapper.width / 2f
            rowsWrapper.pivotY = rowsWrapper.height / 2f
            rowsWrapper.rotation = -12f
            rowsWrapper.scaleX = 1.3f
            rowsWrapper.scaleY = 1.3f
        }

        rowContainers.forEachIndexed { index, row ->
            row?.let { setupRow(it, index) }
        }
    }

    private fun setupRow(row: LinearLayout, rowIndex: Int) {
        row.removeAllViews()
        
        val startIndex = (rowIndex * 2) % chipData.size
        
        // Pre-create ALL chip views with full content
        val chipViews = mutableListOf<TextView>()
        // 4 complete sets for seamless looping with buffer
        repeat(4) {
            for (i in chipData.indices) {
                val chip = chipData[(startIndex + i) % chipData.size]
                chipViews.add(createChipView(chip))
            }
        }
        
        // Add all pre-rendered chips
        chipViews.forEach { row.addView(it) }
        
        // Start animation after layout
        row.post {
            // Force a layout pass to ensure all chips are measured
            row.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            startRowAnimation(row, rowIndex)
        }
    }

    fun stop() {
        isStarted = false
        animators.forEach { it.cancel() }
        animators.clear()
    }

    private fun createChipView(chip: ChipItem): TextView {
        val density = context.resources.displayMetrics.density
        
        return TextView(context).apply {
            text = "${chip.emoji}  ${chip.name}"
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if (chip.darkText) Color.BLACK else Color.WHITE)
            setBackgroundResource(chip.backgroundRes)
            
            val hPadding = (16 * density).toInt()
            val vPadding = (10 * density).toInt()
            setPadding(hPadding, vPadding, hPadding, vPadding)
            
            gravity = Gravity.CENTER
            maxLines = 1
            isSingleLine = true
            
            val margin = (8 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(margin, 0, margin, 0)
            }
        }
    }

    private fun startRowAnimation(row: LinearLayout, rowIndex: Int) {
        val rowWidth = row.width.toFloat()
        if (rowWidth <= 0) return

        val screenWidth = context.resources.displayMetrics.widthPixels.toFloat()
        
        // Width of one set of chips (we have 4 sets)
        val chipSetWidth = rowWidth / 4f
        
        // START OFF-SCREEN to the right - chips render before entering viewport
        // This ensures no empty circles are ever visible
        val startX = screenWidth * 0.3f + (chipSetWidth * rowStartOffsets[rowIndex])
        
        row.translationX = startX

        val animator = ValueAnimator.ofFloat(0f, chipSetWidth).apply {
            duration = rowDurations[rowIndex]
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            
            addUpdateListener { animation ->
                val value = animation.animatedValue as Float
                row.translationX = startX - value
            }
        }

        animators.add(animator)
        animator.start()
    }
}
