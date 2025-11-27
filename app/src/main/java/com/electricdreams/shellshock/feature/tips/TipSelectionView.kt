package com.electricdreams.shellshock.feature.tips

import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.electricdreams.shellshock.R
import com.electricdreams.shellshock.core.model.Amount
import com.electricdreams.shellshock.ui.components.FlowLayout

/**
 * A beautiful, Apple-like tip selection component.
 * Features preset buttons and custom tip input.
 */
class TipSelectionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    interface TipSelectionListener {
        fun onTipSelected(tipAmountSats: Long, tipPercentage: Int)
        fun onTipCleared()
    }

    private var listener: TipSelectionListener? = null
    private var baseAmountSats: Long = 0
    private var baseCurrency: Amount.Currency = Amount.Currency.USD
    private var bitcoinPrice: Double = 0.0
    
    private var presets: List<Int> = listOf(5, 10, 15, 20)
    private var selectedIndex: Int = 0 // 0 = no tip, 1-4 = presets, 5 = custom
    
    // UI Components
    private val buttonsContainer: FlowLayout
    private val customContainer: LinearLayout
    private val customInput: EditText
    private val currencyToggle: TextView
    private var allButtons: MutableList<TextView> = mutableListOf()

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        
        // Buttons container with flow layout
        buttonsContainer = FlowLayout(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            setSpacing(dpToPx(6), dpToPx(8))
            setPadding(0, 0, 0, 0)
        }
        addView(buttonsContainer)
        
        // Custom tip container
        customContainer = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dpToPx(12)
            }
        }
        
        // Custom input field
        customInput = EditText(context).apply {
            layoutParams = LayoutParams(dpToPx(80), dpToPx(40))
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "0.00"
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.color_text_primary))
            setHintTextColor(ContextCompat.getColor(context, R.color.color_text_tertiary))
            background = ContextCompat.getDrawable(context, R.drawable.bg_input_field_rounded)
            setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
            imeOptions = EditorInfo.IME_ACTION_DONE
            
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    hideKeyboard()
                    true
                } else false
            }
            
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    processCustomInput()
                }
            })
        }
        customContainer.addView(customInput)
        
        // Currency toggle button
        currencyToggle = TextView(context).apply {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, dpToPx(40)).apply {
                marginStart = dpToPx(8)
            }
            text = "$"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dpToPx(12), 0, dpToPx(12), 0)
            setTextColor(ContextCompat.getColor(context, R.color.color_accent_blue))
            background = ContextCompat.getDrawable(context, R.drawable.bg_chip_outlined)
            setOnClickListener { toggleCurrency() }
        }
        customContainer.addView(currencyToggle)
        
        addView(customContainer)
    }

    fun configure(
        baseAmountSats: Long,
        baseCurrency: Amount.Currency,
        baseAmountFiat: Long = 0,
        bitcoinPrice: Double = 0.0,
        presets: List<Int> = listOf(5, 10, 15, 20)
    ) {
        this.baseAmountSats = baseAmountSats
        this.baseCurrency = baseCurrency
        this.bitcoinPrice = bitcoinPrice
        this.presets = presets
        
        updateCurrencyToggle()
        buildButtons()
    }

    fun setListener(listener: TipSelectionListener?) {
        this.listener = listener
    }

    private fun buildButtons() {
        buttonsContainer.removeAllViews()
        allButtons.clear()
        
        // Create all buttons
        val buttonTexts = listOf("No tip") + presets.map { "$it%" } + listOf("Custom")
        
        buttonTexts.forEachIndexed { index, text ->
            val button = createButton(text, index)
            allButtons.add(button)
            buttonsContainer.addView(button)
        }
        
        // Select "No tip" by default
        selectButton(0)
    }

    private fun createButton(text: String, index: Int): TextView {
        return TextView(context).apply {
            layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dpToPx(36)).apply {
                marginStart = dpToPx(3)
                marginEnd = dpToPx(3)
                topMargin = dpToPx(3)
                bottomMargin = dpToPx(3)
            }
            this.text = text
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            
            setOnClickListener { selectButton(index) }
        }
    }

    private fun selectButton(index: Int) {
        selectedIndex = index
        
        // Update all button states
        allButtons.forEachIndexed { i, button ->
            if (i == index) {
                button.background = ContextCompat.getDrawable(context, R.drawable.bg_tip_button_selected)
                button.setTextColor(ContextCompat.getColor(context, R.color.color_bg_white))
            } else {
                button.background = ContextCompat.getDrawable(context, R.drawable.bg_tip_button_unselected)
                button.setTextColor(ContextCompat.getColor(context, R.color.color_text_primary))
            }
        }
        
        when (index) {
            0 -> {
                // No tip
                customContainer.visibility = View.GONE
                listener?.onTipCleared()
            }
            in 1..presets.size -> {
                // Preset percentage
                customContainer.visibility = View.GONE
                val percentage = presets[index - 1]
                val tipSats = (baseAmountSats * percentage) / 100
                listener?.onTipSelected(tipSats, percentage)
            }
            presets.size + 1 -> {
                // Custom
                customContainer.visibility = View.VISIBLE
                customInput.requestFocus()
                showKeyboard()
                processCustomInput()
            }
        }
    }

    private fun processCustomInput() {
        if (selectedIndex != presets.size + 1) return // Only process if custom is selected
        
        val inputText = customInput.text.toString()
        if (inputText.isEmpty() || inputText == ".") {
            listener?.onTipCleared()
            return
        }
        
        try {
            val inputValue = inputText.toDouble()
            val tipSats = if (baseCurrency == Amount.Currency.BTC) {
                // Input is in sats
                inputValue.toLong()
            } else {
                // Convert fiat to sats using bitcoin price
                if (bitcoinPrice > 0) {
                    ((inputValue / bitcoinPrice) * 100_000_000).toLong()
                } else {
                    0
                }
            }
            
            if (tipSats > 0) {
                listener?.onTipSelected(tipSats, 0)
            } else {
                listener?.onTipCleared()
            }
        } catch (e: NumberFormatException) {
            listener?.onTipCleared()
        }
    }

    private fun toggleCurrency() {
        // Toggle between fiat and sats for custom input
        updateCurrencyToggle()
        customInput.text.clear()
    }

    private fun updateCurrencyToggle() {
        currencyToggle.text = if (baseCurrency == Amount.Currency.BTC) "₿" else baseCurrency.symbol
    }

    private fun showKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(customInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(customInput.windowToken, 0)
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
}
