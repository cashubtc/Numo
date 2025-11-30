package com.electricdreams.numo.ui.components

import android.content.Context
import android.view.LayoutInflater
import android.widget.Button
import android.widget.GridLayout
import com.electricdreams.numo.R
import com.electricdreams.numo.ui.util.VibrationHelper

/**
 * Manages keypad creation, input handling, and vibration feedback.
 */
class KeypadManager(
    private val context: Context,
    private val keypad: GridLayout,
    private val onKeyPressed: (String) -> Unit
) {

    init {
        setupKeypad()
    }

    /** Create and setup the keypad buttons */
    private fun setupKeypad() {
        val buttonLabels = arrayOf(
            "1", "2", "3",
            "4", "5", "6",
            "7", "8", "9",
            "C", "0", "<",
        )

        val inflater = LayoutInflater.from(context)
        for (label in buttonLabels) {
            val button = inflater.inflate(R.layout.keypad_button_green_screen, keypad, false) as Button
            button.text = label
            button.setOnClickListener { 
                vibrateKeypad()
                onKeyPressed(label)
            }
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = 0
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(4, 2, 4, 2)
            }
            button.layoutParams = params
            keypad.addView(button)
        }
    }

    /** Handle keypad button clicks and update input */
    fun handleKeypadInput(
        label: String,
        satoshiInput: StringBuilder,
        fiatInput: StringBuilder,
        isUsdInputMode: Boolean
    ) {
        when (label) {
            "C" -> {
                // Clear both inputs when clearing
                satoshiInput.clear()
                fiatInput.clear()
            }
            "<" -> {
                val currentInput = if (isUsdInputMode) fiatInput else satoshiInput
                if (currentInput.isNotEmpty()) {
                    currentInput.setLength(currentInput.length - 1)
                }
            }
            else -> {
                val currentInput = if (isUsdInputMode) fiatInput else satoshiInput
                // Limit based on current input mode
                if (isUsdInputMode) {
                    // For fiat, allow up to 12 digits (for very large amounts like $99,999,999.99)
                    if (currentInput.length < 12) {
                        currentInput.append(label)
                    }
                } else {
                    // For satoshis, 9 digit limit for ~21M BTC max
                    if (currentInput.length < 9) {
                        currentInput.append(label)
                    }
                }
            }
        }
    }

    /** Vibrate for keypad feedback */
    private fun vibrateKeypad() {
        VibrationHelper.performClick(context)
    }
}
