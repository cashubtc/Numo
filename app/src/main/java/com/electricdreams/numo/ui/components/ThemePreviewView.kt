package com.electricdreams.numo.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.provider.Settings
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.animation.DecelerateInterpolator
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.Button
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils

import com.electricdreams.numo.R
import com.electricdreams.numo.databinding.ActivityModernPosBinding
import com.electricdreams.numo.ui.theme.ThemeManager

/** A miniature of the POS layout with sample data and no payment actions. */
class ThemePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    private val preview = ActivityModernPosBinding.inflate(LayoutInflater.from(context))
    private val screenBackground = android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = 28 * resources.displayMetrics.density
    }
    private var animator: ValueAnimator? = null
    private var backgroundColor = ThemeManager.resolveBackgroundColor(context, "green")
    private var foregroundColor = Color.WHITE
    private var buttonColor = ContextCompat.getColor(context, R.color.color_white_34)
    private var buttonTextColor = Color.WHITE
    private val buttonBackground = android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = 32 * resources.displayMetrics.density
    }

    init {
        addView(preview.root)
        preview.root.background = screenBackground
        preview.root.clipToOutline = true
        preview.root.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        preview.root.descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        KeypadManager(context, preview.keypad) { }
        preview.amountDisplay.text = java.text.NumberFormat.getCurrencyInstance().apply {
            currency = java.util.Currency.getInstance("USD")
        }.format(24)
        preview.secondaryAmountDisplay.text = "24,000 sats"
        preview.submitButton.background = buttonBackground
        setTheme("green", animate = false)
    }

    fun setTheme(theme: String, animate: Boolean = true) {
        animator?.cancel()
        val targetBackground = ThemeManager.resolveBackgroundColor(context, theme)
        val dark = ContextCompat.getColor(context, R.color.color_theme_text_dark)
        val targetForeground = if (theme == "white") dark else Color.WHITE
        val targetButton = when (theme) {
            "white" -> Color.BLACK
            "obsidian" -> Color.WHITE
            else -> ContextCompat.getColor(context, R.color.color_white_34)
        }
        val targetButtonText = if (theme == "obsidian") dark else Color.WHITE
        val start = intArrayOf(backgroundColor, foregroundColor, buttonColor, buttonTextColor)
        fun update(fraction: Float) {
            backgroundColor = ColorUtils.blendARGB(start[0], targetBackground, fraction)
            foregroundColor = ColorUtils.blendARGB(start[1], targetForeground, fraction)
            buttonColor = ColorUtils.blendARGB(start[2], targetButton, fraction)
            buttonTextColor = ColorUtils.blendARGB(start[3], targetButtonText, fraction)
            screenBackground.setColor(backgroundColor)
            preview.amountDisplay.setTextColor(foregroundColor)
            preview.secondaryAmountDisplay.setTextColor(foregroundColor)
            listOf(preview.actionCatalog, preview.actionHistory, preview.actionSettings,
                preview.currencySwitchButton).forEach { it.setColorFilter(foregroundColor) }
            for (index in 0 until preview.keypad.childCount) {
                (preview.keypad.getChildAt(index) as? Button)?.setTextColor(foregroundColor)
            }
            buttonBackground.setColor(buttonColor)
            preview.submitButton.setTextColor(buttonTextColor)
            invalidate()
        }
        val animationsEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ValueAnimator.areAnimatorsEnabled()
        } else {
            Settings.Global.getFloat(context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
        }
        if (animate && isAttachedToWindow && animationsEnabled) {
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 240
                interpolator = DecelerateInterpolator()
                addUpdateListener { update(it.animatedValue as Float) }
                start()
            }
        } else {
            update(1f)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec),
            MeasureSpec.getSize(heightMeasureSpec))
        val density = resources.displayMetrics.density
        preview.root.measure(
            MeasureSpec.makeMeasureSpec((360 * density).toInt(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec((700 * density).toInt(), MeasureSpec.EXACTLY)
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        preview.root.layout(0, 0, preview.root.measuredWidth, preview.root.measuredHeight)
        val inset = 16 * resources.displayMetrics.density
        val scale = minOf((width - 2 * inset) / preview.root.width,
            (height - 2 * inset) / preview.root.height).coerceAtLeast(0f)
        preview.root.pivotX = 0f
        preview.root.pivotY = 0f
        preview.root.scaleX = scale
        preview.root.scaleY = scale
        preview.root.translationX = (width - preview.root.width * scale) / 2
        preview.root.translationY = (height - preview.root.height * scale) / 2
    }

    // The sample is visual only. Keep its controls out of touch and keyboard navigation.
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = true

    override fun onDetachedFromWindow() {
        animator?.end()
        animator = null
        super.onDetachedFromWindow()
    }
}
