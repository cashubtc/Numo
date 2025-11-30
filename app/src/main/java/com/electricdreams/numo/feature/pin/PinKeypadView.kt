package com.electricdreams.numo.feature.pin

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.Button
import android.widget.GridLayout
import android.widget.ImageButton
import androidx.core.content.ContextCompat
import com.electricdreams.numo.R
import com.electricdreams.numo.ui.util.VibrationHelper

/**
 * Custom numeric keypad for PIN entry, styled to match the POS keypad.
 * This is a GridLayout that creates digit buttons 1-9, 0, and backspace.
 * Features haptic feedback on button press.
 */
class PinKeypadView @JvmOverloads constructor( 
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : GridLayout(context, attrs, defStyleAttr) {

    private var onKeyListener: OnKeyListener? = null

    interface OnKeyListener {
        fun onDigitPressed(digit: String)
        fun onDeletePressed()
    }

    init {
        // Set GridLayout properties
        columnCount = 3
        rowCount = 4
        useDefaultMargins = false
        alignmentMode = ALIGN_BOUNDS

        setupKeypad()
    }

    fun setOnKeyListener(listener: OnKeyListener) {
        this.onKeyListener = listener
    }

    private fun setupKeypad() {
        val buttonLabels = arrayOf(
            "1", "2", "3",
            "4", "5", "6",
            "7", "8", "9",
            "", "0", "⌫"
        )

        val inflater = LayoutInflater.from(context)

        for (i in buttonLabels.indices) {
            val label = buttonLabels[i]
            val row = i / 3
            val col = i % 3

            when {
                label.isEmpty() -> {
                    // Empty placeholder for bottom-left
                    val spacer = android.widget.Space(context)
                    val params = LayoutParams(
                        spec(row, 1f),
                        spec(col, 1f)
                    ).apply {
                        width = 0
                        height = 0
                        setMargins(4, 4, 4, 4)
                    }
                    spacer.layoutParams = params
                    addView(spacer)
                }
                label == "⌫" -> {
                    // Delete button with icon - uses same ripple effect as digit buttons
                    val deleteButton = ImageButton(context).apply {
                        setImageResource(R.drawable.ic_backspace)
                        setColorFilter(ContextCompat.getColor(context, R.color.color_text_secondary))
                        // Use selectableItemBackgroundBorderless for ripple effect like POS keypad
                        val attrs = intArrayOf(android.R.attr.selectableItemBackgroundBorderless)
                        val typedArray = context.obtainStyledAttributes(attrs)
                        background = typedArray.getDrawable(0)
                        typedArray.recycle()
                        scaleType = android.widget.ImageView.ScaleType.CENTER
                        contentDescription = "Delete"
                        setOnClickListener {
                            vibrateKeypad()
                            onKeyListener?.onDeletePressed()
                        }
                    }
                    val params = LayoutParams(
                        spec(row, 1f),
                        spec(col, 1f)
                    ).apply {
                        width = 0
                        height = 0
                        setMargins(4, 4, 4, 4)
                    }
                    deleteButton.layoutParams = params
                    addView(deleteButton)
                }
                else -> {
                    // Digit button - inflate from layout like KeypadManager does
                    val button = inflater.inflate(R.layout.keypad_button_pin, this, false) as Button
                    button.text = label
                    button.setOnClickListener {
                        vibrateKeypad()
                        onKeyListener?.onDigitPressed(label)
                    }
                    val params = LayoutParams(
                        spec(row, 1f),
                        spec(col, 1f)
                    ).apply {
                        width = 0
                        height = 0
                        setMargins(4, 4, 4, 4)
                    }
                    button.layoutParams = params
                    addView(button)
                }
            }
        }
    }

    private fun vibrateKeypad() {
        VibrationHelper.performClick(context)
    }
}
