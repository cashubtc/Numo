package com.electricdreams.shellshock.feature.tips

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.electricdreams.shellshock.PaymentRequestActivity
import com.electricdreams.shellshock.R
import com.electricdreams.shellshock.core.model.Amount
import com.electricdreams.shellshock.core.model.Amount.Currency
import com.electricdreams.shellshock.core.worker.BitcoinPriceWorker
import kotlin.math.roundToLong

/**
 * A beautiful, Apple-like tip selection screen.
 * Shows before PaymentRequestActivity when tips are enabled.
 */
class TipSelectionActivity : AppCompatActivity() {

    // Intent extras
    private var paymentAmountSats: Long = 0
    private var formattedAmount: String = ""
    private var entryCurrency: Currency = Currency.USD
    private var enteredAmountFiat: Long = 0
    private var checkoutBasketJson: String? = null

    // State
    private var selectedTipSats: Long = 0
    private var selectedTipPercentage: Int = 0
    private var isCustomMode: Boolean = false
    private var bitcoinPrice: Double = 0.0
    private var presets: List<Int> = listOf()

    // Custom tip input state
    private var customInputIsBtc: Boolean = false
    private var customInputValue: String = ""

    // Views
    private lateinit var amountDisplay: TextView
    private lateinit var convertedAmountDisplay: TextView
    private lateinit var questionText: TextView
    private lateinit var tipOptionsContainer: LinearLayout
    private lateinit var customInputContainer: LinearLayout
    private lateinit var customAmountDisplay: TextView
    private lateinit var customCurrencyPrefix: TextView
    private lateinit var customCurrencyToggle: TextView
    private lateinit var customKeypad: GridLayout
    private lateinit var customButtonsContainer: View
    private lateinit var customConfirmButton: Button
    private lateinit var customBackButton: Button
    private lateinit var noTipButton: View
    private lateinit var confirmButton: Button
    private lateinit var customTipButton: View

    // Preset buttons
    private val presetButtons = mutableListOf<View>()
    private var selectedPresetButton: View? = null
    
    // Keypad decimal button reference
    private var decimalKeyButton: View? = null
    
    // Track if confirm button is showing (for animation)
    private var isConfirmButtonShowing = false

    private var bitcoinPriceWorker: BitcoinPriceWorker? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tip_selection)

        setupWindowSettings()
        initializeFromIntent()
        initializeViews()
        setupPresetButtons()
        setupCustomKeypad()
        setupClickListeners()
        animateEntrance()
    }

    private fun setupWindowSettings() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        // Light status bar (dark icons) since we have white background
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, insets.top, 0, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun initializeFromIntent() {
        paymentAmountSats = intent.getLongExtra(EXTRA_PAYMENT_AMOUNT, 0)
        formattedAmount = intent.getStringExtra(EXTRA_FORMATTED_AMOUNT) ?: ""
        checkoutBasketJson = intent.getStringExtra(EXTRA_CHECKOUT_BASKET_JSON)

        // Parse entry currency
        val parsedAmount = Amount.parse(formattedAmount)
        if (parsedAmount != null) {
            entryCurrency = parsedAmount.currency
            if (entryCurrency != Currency.BTC) {
                enteredAmountFiat = parsedAmount.value
            }
        }

        // Set default for custom input based on entry currency
        customInputIsBtc = (entryCurrency == Currency.BTC)

        // Get Bitcoin price
        bitcoinPriceWorker = BitcoinPriceWorker.getInstance(this)
        bitcoinPrice = bitcoinPriceWorker?.getCurrentPrice() ?: 0.0

        // Get tip presets
        val tipsManager = TipsManager.getInstance(this)
        presets = tipsManager.getTipPresets()
    }

    private fun initializeViews() {
        amountDisplay = findViewById(R.id.amount_display)
        convertedAmountDisplay = findViewById(R.id.converted_amount_display)
        questionText = findViewById(R.id.question_text)
        tipOptionsContainer = findViewById(R.id.tip_options_container)
        customInputContainer = findViewById(R.id.custom_input_container)
        customAmountDisplay = findViewById(R.id.custom_amount_display)
        customCurrencyPrefix = findViewById(R.id.custom_currency_prefix)
        customCurrencyToggle = findViewById(R.id.custom_currency_toggle)
        customKeypad = findViewById(R.id.custom_keypad)
        customButtonsContainer = findViewById(R.id.custom_buttons_container)
        customConfirmButton = findViewById(R.id.custom_confirm_button)
        customBackButton = findViewById(R.id.custom_back_button)
        noTipButton = findViewById(R.id.no_tip_button)
        confirmButton = findViewById(R.id.confirm_button)
        customTipButton = findViewById(R.id.custom_tip_button)

        // Display the payment amount
        amountDisplay.text = formattedAmount
        updateConvertedAmount()
        updateCustomCurrencyDisplay()
    }

    private fun updateConvertedAmount() {
        val isBtcAmount = formattedAmount.startsWith("₿")
        
        if (bitcoinPrice <= 0) {
            convertedAmountDisplay.visibility = View.GONE
            return
        }

        if (isBtcAmount) {
            val fiatValue = bitcoinPriceWorker?.satoshisToFiat(paymentAmountSats) ?: 0.0
            if (fiatValue > 0) {
                convertedAmountDisplay.text = bitcoinPriceWorker?.formatFiatAmount(fiatValue)
                convertedAmountDisplay.visibility = View.VISIBLE
            } else {
                convertedAmountDisplay.visibility = View.GONE
            }
        } else {
            if (paymentAmountSats > 0) {
                convertedAmountDisplay.text = Amount(paymentAmountSats, Currency.BTC).toString()
                convertedAmountDisplay.visibility = View.VISIBLE
            } else {
                convertedAmountDisplay.visibility = View.GONE
            }
        }
    }

    private fun setupPresetButtons() {
        val container = findViewById<LinearLayout>(R.id.preset_buttons_container)
        container.removeAllViews()
        presetButtons.clear()

        // Create two rows of buttons
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(12)
            }
        }

        presets.forEachIndexed { index, percentage ->
            val button = createPresetButton(percentage, index)
            presetButtons.add(button)
            
            if (index < 2) {
                row1.addView(button)
            } else {
                row2.addView(button)
            }
        }

        container.addView(row1)
        if (presets.size > 2) {
            container.addView(row2)
        }
    }

    private fun createPresetButton(percentage: Int, index: Int): View {
        val button = layoutInflater.inflate(R.layout.item_tip_preset_large, null)
        
        val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = if (index % 2 == 0) 0 else dpToPx(6)
            marginEnd = if (index % 2 == 1) 0 else dpToPx(6)
        }
        button.layoutParams = params

        val percentageText = button.findViewById<TextView>(R.id.percentage_text)
        val totalText = button.findViewById<TextView>(R.id.total_text)

        percentageText.text = "$percentage%"
        
        // Calculate tip and total
        val tipSats = (paymentAmountSats * percentage) / 100
        val totalSats = paymentAmountSats + tipSats
        totalText.text = formatTotalAmount(totalSats)
        totalText.visibility = View.VISIBLE

        button.tag = Pair(percentage, tipSats) // Store data in tag

        button.setOnClickListener {
            selectPreset(percentage, tipSats, button)
        }

        return button
    }

    private fun formatTotalAmount(totalSats: Long): String {
        return if (entryCurrency == Currency.BTC) {
            Amount(totalSats, Currency.BTC).toString()
        } else {
            if (bitcoinPrice > 0) {
                val fiatValue = (totalSats.toDouble() / 100_000_000.0) * bitcoinPrice
                bitcoinPriceWorker?.formatFiatAmount(fiatValue) ?: "$${String.format("%.2f", fiatValue)}"
            } else {
                Amount(totalSats, Currency.BTC).toString()
            }
        }
    }

    private fun selectPreset(percentage: Int, tipSats: Long, selectedButton: View) {
        selectedTipPercentage = percentage
        selectedTipSats = tipSats
        selectedPresetButton = selectedButton

        // Update button states with animation
        presetButtons.forEach { button ->
            val isSelected = button == selectedButton
            animateButtonSelection(button, isSelected)
        }
        // Deselect custom tip button
        animateCustomButtonSelection(false)

        // Enable confirm button
        updateMainConfirmButton(true)
    }

    private fun animateButtonSelection(button: View, isSelected: Boolean) {
        val scale = if (isSelected) 1.02f else 1f
        val background = if (isSelected) {
            R.drawable.bg_tip_button_large_selected
        } else {
            R.drawable.bg_tip_button_large
        }

        button.animate()
            .scaleX(scale)
            .scaleY(scale)
            .setDuration(150)
            .setInterpolator(DecelerateInterpolator())
            .start()

        button.setBackgroundResource(background)

        // Update text colors
        val percentageText = button.findViewById<TextView>(R.id.percentage_text)
        val totalText = button.findViewById<TextView>(R.id.total_text)
        
        if (isSelected) {
            percentageText?.setTextColor(Color.WHITE)
            totalText?.setTextColor(Color.WHITE)
        } else {
            percentageText?.setTextColor(ContextCompat.getColor(this, R.color.color_text_primary))
            totalText?.setTextColor(ContextCompat.getColor(this, R.color.color_text_secondary))
        }
    }

    private fun animateCustomButtonSelection(isSelected: Boolean) {
        val scale = if (isSelected) 1.02f else 1f
        val background = if (isSelected) {
            R.drawable.bg_tip_button_large_selected
        } else {
            R.drawable.bg_tip_button_large
        }

        customTipButton.animate()
            .scaleX(scale)
            .scaleY(scale)
            .setDuration(150)
            .setInterpolator(DecelerateInterpolator())
            .start()

        customTipButton.setBackgroundResource(background)

        // Update text colors
        val labelText = customTipButton.findViewById<TextView>(R.id.custom_tip_label)
        val subtitleText = customTipButton.findViewById<TextView>(R.id.custom_tip_subtitle)
        
        if (isSelected) {
            labelText?.setTextColor(Color.WHITE)
            subtitleText?.setTextColor(Color.WHITE)
        } else {
            labelText?.setTextColor(ContextCompat.getColor(this, R.color.color_text_primary))
            subtitleText?.setTextColor(ContextCompat.getColor(this, R.color.color_text_secondary))
        }
    }

    private fun setupCustomKeypad() {
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", ".", "0", "⌫")
        
        customKeypad.removeAllViews()
        customKeypad.columnCount = 3
        customKeypad.rowCount = 4

        keys.forEach { key ->
            val keyButton = layoutInflater.inflate(R.layout.keypad_button_tip, customKeypad, false)
            val keyText = keyButton.findViewById<TextView>(R.id.key_text)
            keyText.text = key

            keyButton.setOnClickListener {
                onKeypadPress(key)
            }

            // Store reference to decimal key
            if (key == ".") {
                decimalKeyButton = keyButton
            }

            customKeypad.addView(keyButton)
        }
        
        // Update decimal key visibility based on initial currency mode
        updateDecimalKeyVisibility()
    }
    
    private fun updateDecimalKeyVisibility() {
        decimalKeyButton?.let { button ->
            val keyText = button.findViewById<TextView>(R.id.key_text)
            if (customInputIsBtc) {
                // Hide decimal for Bitcoin (sats are whole numbers)
                keyText.alpha = 0f
                button.isClickable = false
                button.isFocusable = false
            } else {
                // Show decimal for fiat
                keyText.alpha = 1f
                button.isClickable = true
                button.isFocusable = true
            }
        }
    }

    private fun onKeypadPress(key: String) {
        when (key) {
            "⌫" -> {
                if (customInputValue.isNotEmpty()) {
                    customInputValue = customInputValue.dropLast(1)
                }
            }
            "." -> {
                // Only allow decimal for fiat, not for BTC (sats are whole numbers)
                if (!customInputIsBtc && !customInputValue.contains(".") && customInputValue.isNotEmpty()) {
                    customInputValue += "."
                } else if (!customInputIsBtc && customInputValue.isEmpty()) {
                    customInputValue = "0."
                }
            }
            else -> {
                // Limit decimal places
                val decimalIndex = customInputValue.indexOf(".")
                if (decimalIndex >= 0) {
                    val decimals = customInputValue.length - decimalIndex - 1
                    val maxDecimals = 2 // Fiat has 2 decimals
                    if (decimals >= maxDecimals) return
                }
                
                // Limit total length
                if (customInputValue.replace(".", "").length >= 10) return
                
                customInputValue += key
            }
        }

        updateCustomAmountDisplay()
        calculateCustomTip()
    }

    private fun updateCustomAmountDisplay() {
        val displayValue = if (customInputValue.isEmpty()) {
            "0"
        } else {
            customInputValue
        }

        customAmountDisplay.text = displayValue
        
        // Animate text size based on length
        val textSize = when {
            displayValue.length <= 5 -> 56f
            displayValue.length <= 7 -> 48f
            displayValue.length <= 9 -> 40f
            else -> 32f
        }
        
        customAmountDisplay.textSize = textSize
    }

    private fun updateCustomCurrencyDisplay() {
        if (customInputIsBtc) {
            customCurrencyPrefix.text = "₿"
            customCurrencyToggle.text = "Switch to ${entryCurrency.symbol}"
        } else {
            customCurrencyPrefix.text = entryCurrency.symbol
            customCurrencyToggle.text = "Switch to ₿"
        }
    }

    private fun calculateCustomTip() {
        if (customInputValue.isEmpty() || customInputValue == "." || customInputValue == "0") {
            selectedTipSats = 0
            selectedTipPercentage = 0
            updateCustomConfirmButton(false)
            return
        }

        try {
            val inputValue = customInputValue.toDouble()
            
            selectedTipSats = if (customInputIsBtc) {
                inputValue.toLong() // Direct sats
            } else {
                if (bitcoinPrice > 0) {
                    ((inputValue / bitcoinPrice) * 100_000_000).roundToLong()
                } else {
                    0L
                }
            }
            
            selectedTipPercentage = 0 // Custom tip has no percentage
            updateCustomConfirmButton(selectedTipSats > 0)
        } catch (e: NumberFormatException) {
            selectedTipSats = 0
            selectedTipPercentage = 0
            updateCustomConfirmButton(false)
        }
    }

    private fun setupClickListeners() {
        // Close button
        findViewById<View>(R.id.close_button).setOnClickListener {
            onBackPressed()
        }

        // Custom tip button
        customTipButton.setOnClickListener {
            showCustomInputMode()
        }

        // No tip button
        noTipButton.setOnClickListener {
            selectedTipSats = 0
            selectedTipPercentage = 0
            proceedToPayment()
        }

        // Main confirm button
        confirmButton.setOnClickListener {
            if (selectedTipSats > 0 || selectedTipPercentage > 0) {
                proceedToPayment()
            }
        }

        // Custom mode back button
        customBackButton.setOnClickListener {
            showTipOptionsMode()
        }

        // Custom mode confirm button
        customConfirmButton.setOnClickListener {
            if (selectedTipSats > 0) {
                proceedToPayment()
            }
        }

        // Currency toggle in custom mode
        customCurrencyToggle.setOnClickListener {
            toggleCustomCurrency()
        }
    }

    private fun toggleCustomCurrency() {
        customInputIsBtc = !customInputIsBtc
        customInputValue = ""
        updateCustomAmountDisplay()
        updateCustomCurrencyDisplay()
        updateDecimalKeyVisibility()
        calculateCustomTip()

        // Animate the toggle
        customCurrencyToggle.animate()
            .scaleX(0.9f)
            .scaleY(0.9f)
            .setDuration(100)
            .withEndAction {
                customCurrencyToggle.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(100)
                    .start()
            }
            .start()
    }

    private fun showCustomInputMode() {
        isCustomMode = true
        customInputValue = ""
        isConfirmButtonShowing = false // Reset button state
        updateCustomAmountDisplay()
        updateCustomCurrencyDisplay()
        updateDecimalKeyVisibility()

        // Deselect all preset buttons
        presetButtons.forEach { animateButtonSelection(it, false) }
        selectedPresetButton = null
        
        // Reset button layout to initial state (Back full width, Confirm hidden)
        customConfirmButton.visibility = View.GONE
        val backParams = customBackButton.layoutParams as LinearLayout.LayoutParams
        backParams.width = LinearLayout.LayoutParams.MATCH_PARENT
        backParams.weight = 0f
        backParams.marginEnd = 0
        customBackButton.layoutParams = backParams

        // Hide tip options, show custom input
        tipOptionsContainer.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction {
                tipOptionsContainer.visibility = View.GONE
                confirmButton.visibility = View.GONE
                
                customInputContainer.visibility = View.VISIBLE
                customInputContainer.alpha = 0f
                customButtonsContainer.visibility = View.VISIBLE
                customButtonsContainer.alpha = 0f
                
                customInputContainer.animate()
                    .alpha(1f)
                    .setDuration(200)
                    .start()
                customButtonsContainer.animate()
                    .alpha(1f)
                    .setDuration(200)
                    .start()
            }
            .start()

        // Update question text
        questionText.animate()
            .alpha(0f)
            .setDuration(150)
            .withEndAction {
                questionText.text = "Enter tip amount"
                questionText.animate()
                    .alpha(1f)
                    .setDuration(150)
                    .start()
            }
            .start()
    }

    private fun showTipOptionsMode() {
        isCustomMode = false
        selectedTipSats = 0
        selectedTipPercentage = 0

        // Hide custom input, show tip options
        customInputContainer.animate()
            .alpha(0f)
            .setDuration(200)
            .start()
        customButtonsContainer.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction {
                customInputContainer.visibility = View.GONE
                customButtonsContainer.visibility = View.GONE
                
                tipOptionsContainer.visibility = View.VISIBLE
                tipOptionsContainer.alpha = 0f
                confirmButton.visibility = View.VISIBLE
                confirmButton.alpha = 0.4f
                
                tipOptionsContainer.animate()
                    .alpha(1f)
                    .setDuration(200)
                    .start()
            }
            .start()

        // Update question text
        questionText.animate()
            .alpha(0f)
            .setDuration(150)
            .withEndAction {
                questionText.text = "Would you like to add a tip?"
                questionText.animate()
                    .alpha(1f)
                    .setDuration(150)
                    .start()
            }
            .start()

        updateMainConfirmButton(false)
    }

    private fun updateMainConfirmButton(enabled: Boolean) {
        if (enabled) {
            confirmButton.alpha = 1f
            confirmButton.isEnabled = true
        } else {
            confirmButton.alpha = 0.4f
            confirmButton.isEnabled = false
        }
    }

    private fun updateCustomConfirmButton(enabled: Boolean) {
        val shouldShowConfirm = enabled
        
        if (shouldShowConfirm && !isConfirmButtonShowing) {
            // Animate from full-width Back button to side-by-side layout
            isConfirmButtonShowing = true
            
            // Show confirm button
            customConfirmButton.visibility = View.VISIBLE
            customConfirmButton.alpha = 0f
            customConfirmButton.scaleX = 0.8f
            customConfirmButton.scaleY = 0.8f
            
            // Animate Back button to shrink
            val backParams = customBackButton.layoutParams as LinearLayout.LayoutParams
            backParams.width = 0
            backParams.weight = 1f
            backParams.marginEnd = dpToPx(8)
            customBackButton.layoutParams = backParams
            
            // Animate Confirm button to appear
            val confirmParams = customConfirmButton.layoutParams as LinearLayout.LayoutParams
            confirmParams.width = 0
            confirmParams.weight = 1f
            confirmParams.marginStart = dpToPx(8)
            customConfirmButton.layoutParams = confirmParams
            
            // Smooth animation
            customConfirmButton.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(250)
                .setInterpolator(DecelerateInterpolator())
                .start()
                
        } else if (!shouldShowConfirm && isConfirmButtonShowing) {
            // Animate back to full-width Back button
            isConfirmButtonShowing = false
            
            // Animate Confirm button to disappear
            customConfirmButton.animate()
                .alpha(0f)
                .scaleX(0.8f)
                .scaleY(0.8f)
                .setDuration(200)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    customConfirmButton.visibility = View.GONE
                    
                    // Animate Back button to expand
                    val backParams = customBackButton.layoutParams as LinearLayout.LayoutParams
                    backParams.width = LinearLayout.LayoutParams.MATCH_PARENT
                    backParams.weight = 0f
                    backParams.marginEnd = 0
                    customBackButton.layoutParams = backParams
                }
                .start()
        }
        
        // Update enabled state
        customConfirmButton.isEnabled = enabled
    }

    private fun animateEntrance() {
        // Start with views translated down and faded
        val orderLabel = findViewById<View>(R.id.order_label)
        
        orderLabel.translationY = -30f
        orderLabel.alpha = 0f
        amountDisplay.translationY = -30f
        amountDisplay.alpha = 0f
        questionText.translationY = 30f
        questionText.alpha = 0f
        tipOptionsContainer.translationY = 50f
        tipOptionsContainer.alpha = 0f
        confirmButton.translationY = 30f
        confirmButton.alpha = 0f

        // Animate in sequence
        orderLabel.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(350)
            .setInterpolator(DecelerateInterpolator())
            .start()

        amountDisplay.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(400)
            .setStartDelay(50)
            .setInterpolator(DecelerateInterpolator())
            .start()

        Handler(Looper.getMainLooper()).postDelayed({
            questionText.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(350)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }, 150)

        Handler(Looper.getMainLooper()).postDelayed({
            tipOptionsContainer.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(400)
                .setInterpolator(DecelerateInterpolator())
                .start()

            confirmButton.animate()
                .translationY(0f)
                .alpha(0.4f) // Start disabled
                .setDuration(400)
                .setInterpolator(DecelerateInterpolator())
                .start()

            // Stagger animate each preset button
            presetButtons.forEachIndexed { index, button ->
                button.alpha = 0f
                button.translationY = 30f
                Handler(Looper.getMainLooper()).postDelayed({
                    button.animate()
                        .translationY(0f)
                        .alpha(1f)
                        .setDuration(300)
                        .setInterpolator(OvershootInterpolator(0.8f))
                        .start()
                }, (index * 50).toLong())
            }

            // Animate custom tip button
            customTipButton.alpha = 0f
            customTipButton.translationY = 30f
            Handler(Looper.getMainLooper()).postDelayed({
                customTipButton.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(300)
                    .setInterpolator(OvershootInterpolator(0.8f))
                    .start()
            }, (presetButtons.size * 50 + 50).toLong())
        }, 250)
    }

    private fun proceedToPayment() {
        val totalAmountSats = paymentAmountSats + selectedTipSats
        
        // Calculate new formatted amount (total)
        val newFormattedAmount = if (entryCurrency == Currency.BTC) {
            Amount(totalAmountSats, Currency.BTC).toString()
        } else {
            // For fiat, recalculate the total
            if (enteredAmountFiat > 0 && selectedTipSats > 0) {
                val tipFiat = if (bitcoinPrice > 0) {
                    (selectedTipSats.toDouble() / 100_000_000.0) * bitcoinPrice
                } else {
                    0.0
                }
                val totalFiat = (enteredAmountFiat / 100.0) + tipFiat
                // Format as currency
                val symbol = entryCurrency.symbol
                "$symbol${String.format("%.2f", totalFiat)}"
            } else {
                formattedAmount
            }
        }

        val intent = Intent(this, PaymentRequestActivity::class.java).apply {
            putExtra(PaymentRequestActivity.EXTRA_PAYMENT_AMOUNT, totalAmountSats)
            putExtra(PaymentRequestActivity.EXTRA_FORMATTED_AMOUNT, newFormattedAmount)
            putExtra(EXTRA_TIP_AMOUNT_SATS, selectedTipSats)
            putExtra(EXTRA_TIP_PERCENTAGE, selectedTipPercentage)
            putExtra(EXTRA_BASE_AMOUNT_SATS, paymentAmountSats)
            putExtra(EXTRA_BASE_FORMATTED_AMOUNT, formattedAmount)
            checkoutBasketJson?.let {
                putExtra(PaymentRequestActivity.EXTRA_CHECKOUT_BASKET_JSON, it)
            }
        }
        
        startActivityForResult(intent, REQUEST_CODE_PAYMENT)
        
        // Smooth transition
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == REQUEST_CODE_PAYMENT) {
            // Pass the result back to the caller
            setResult(resultCode, data)
            finish()
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
        }
    }

    override fun onBackPressed() {
        if (isCustomMode) {
            showTipOptionsMode()
        } else {
            setResult(Activity.RESULT_CANCELED)
            super.onBackPressed()
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    companion object {
        const val EXTRA_PAYMENT_AMOUNT = "payment_amount"
        const val EXTRA_FORMATTED_AMOUNT = "formatted_amount"
        const val EXTRA_CHECKOUT_BASKET_JSON = "checkout_basket_json"
        const val EXTRA_TIP_AMOUNT_SATS = "tip_amount_sats"
        const val EXTRA_TIP_PERCENTAGE = "tip_percentage"
        const val EXTRA_BASE_AMOUNT_SATS = "base_amount_sats"
        const val EXTRA_BASE_FORMATTED_AMOUNT = "base_formatted_amount"
        const val REQUEST_CODE_PAYMENT = 1002
    }
}
