package com.electricdreams.shellshock

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AnimationSet
import android.view.animation.AnimationUtils
import android.view.animation.TranslateAnimation
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.PopupMenu
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.electricdreams.shellshock.core.cashu.CashuWalletManager
import com.electricdreams.shellshock.core.model.Amount
import com.electricdreams.shellshock.core.util.CurrencyManager
import com.electricdreams.shellshock.core.util.MintManager
import com.electricdreams.shellshock.core.worker.BitcoinPriceWorker
import com.electricdreams.shellshock.feature.history.PaymentsHistoryActivity
import com.electricdreams.shellshock.feature.items.ItemSelectionActivity
import com.electricdreams.shellshock.feature.settings.SettingsActivity
import com.electricdreams.shellshock.ndef.CashuPaymentHelper
import com.electricdreams.shellshock.ndef.NdefHostCardEmulationService

class ModernPOSActivity : AppCompatActivity(), SatocashWallet.OperationFeedback {

    private lateinit var amountDisplay: TextView
    private lateinit var secondaryAmountDisplay: TextView
    private lateinit var submitButton: Button
    private lateinit var switchCurrencyButton: View
    private lateinit var inputModeContainer: ConstraintLayout
    private lateinit var errorMessage: TextView

    private val satoshiInput = StringBuilder()
    private val fiatInput = StringBuilder() // Stores fiat amount in cents

    private var nfcAdapter: android.nfc.NfcAdapter? = null
    private var satocashClient: SatocashNfcClient? = null
    private var satocashWallet: SatocashWallet? = null
    private var bitcoinPriceWorker: BitcoinPriceWorker? = null

    private var isUsdInputMode: Boolean = false
    private var savedPin: String? = null
    private var waitingForRescan: Boolean = false
    private var rescanDialog: AlertDialog? = null
    private var processingDialog: AlertDialog? = null
    private var requestedAmount: Long = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var vibrator: Vibrator? = null

    private enum class AnimationType { NONE, DIGIT_ENTRY, CURRENCY_SWITCH }

    private object SW {
        const val UNAUTHORIZED = 0x9C06
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Load dark mode setting (affects all screens except home screen)
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val isDarkMode = prefs.getBoolean(KEY_DARK_MODE, false)
        isUsdInputMode = prefs.getBoolean(KEY_INPUT_MODE, false)
        AppCompatDelegate.setDefaultNightMode(
            if (isDarkMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO,
        )

        super.onCreate(savedInstanceState)
        CashuWalletManager.init(this)
        setContentView(R.layout.activity_modern_pos)

        val paymentAmount = intent.getLongExtra("EXTRA_PAYMENT_AMOUNT", 0L)
        Log.d(TAG, "Created ModernPOSActivity with payment amount from basket: $paymentAmount")

        amountDisplay = findViewById(R.id.amount_display)
        secondaryAmountDisplay = findViewById(R.id.secondary_amount_display)
        submitButton = findViewById(R.id.submit_button)
        errorMessage = findViewById(R.id.error_message)
        val keypad: GridLayout = findViewById(R.id.keypad)
        switchCurrencyButton = findViewById(R.id.currency_switch_button)
        inputModeContainer = findViewById(R.id.input_mode_container)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        // Apply theme after views are initialized
        applyTheme()
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, insets.top, 0, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        bitcoinPriceWorker = BitcoinPriceWorker.getInstance(this).also { worker ->
            worker.setPriceUpdateListener(object : BitcoinPriceWorker.PriceUpdateListener {
                override fun onPriceUpdated(price: Double) {
                    if (getCurrentInput().isNotEmpty()) {
                        updateDisplay(AnimationType.NONE)
                    }
                }
            })
            worker.start()
        }

        window.setBackgroundDrawableResource(R.color.color_primary_green)

        // Set up currency toggle - click the entire container
        val secondaryAmountContainer = findViewById<View>(R.id.secondary_amount_container)
        secondaryAmountContainer.setOnClickListener { toggleInputMode() }

        findViewById<ImageButton>(R.id.action_more_options).setOnClickListener { showOverflowMenu(it) }
        findViewById<ImageButton>(R.id.action_history).setOnClickListener {
            startActivity(Intent(this, PaymentsHistoryActivity::class.java))
        }
        findViewById<ImageButton>(R.id.action_catalog).setOnClickListener {
            startActivity(Intent(this, ItemSelectionActivity::class.java))
        }
        findViewById<ImageButton>(R.id.action_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        nfcAdapter = android.nfc.NfcAdapter.getDefaultAdapter(this)
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator?

        val buttonLabels = arrayOf(
            "1", "2", "3",
            "4", "5", "6",
            "7", "8", "9",
            "C", "0", "<",
        )

        val inflater = LayoutInflater.from(this)
        for (label in buttonLabels) {
            val button = inflater.inflate(R.layout.keypad_button_green_screen, keypad, false) as Button
            button.text = label
            button.setOnClickListener { onKeypadButtonClick(label) }
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

        submitButton.setOnClickListener {
            if (requestedAmount > 0) {
                showPaymentMethodDialog(requestedAmount)
            } else {
                showAmountRequiredError()
            }
        }

        resetToInputMode()

        if (paymentAmount > 0) {
            satoshiInput.clear()
            satoshiInput.append(paymentAmount.toString())
            fiatInput.clear()
            requestedAmount = paymentAmount
            updateDisplay(AnimationType.NONE)

            Handler(Looper.getMainLooper()).postDelayed({
                if (submitButton.isEnabled) {
                    Log.d(TAG, "Auto-initiating payment flow for basket checkout with amount: $paymentAmount")
                    showPaymentMethodDialog(paymentAmount)
                }
            }, 500)
        } else {
            updateDisplay(AnimationType.NONE)
        }
    }

    private fun resetToInputMode() {
        inputModeContainer.visibility = View.VISIBLE
        savedPin = null
        waitingForRescan = false
        rescanDialog?.dismiss()
        satoshiInput.clear()
        fiatInput.clear()
        updateDisplay(AnimationType.NONE)
    }

    private fun toggleInputMode() {
        // Check if we can switch to USD (need price data)
        if (!isUsdInputMode) {
            val price = bitcoinPriceWorker?.getCurrentPrice() ?: 0.0
            if (price <= 0) {
                Toast.makeText(this, "Bitcoin price unavailable", Toast.LENGTH_SHORT).show()
                return
            }
        }

        // Convert current input value to the other unit before switching
        // This preserves both values so switching back restores the original
        val currentInputStr = getCurrentInput().toString()
        if (currentInputStr.isNotEmpty()) {
            try {
                if (isUsdInputMode) {
                    // Currently in fiat mode, converting to satoshi mode
                    val fiatCents = currentInputStr.toLong()
                    val fiatAmount = fiatCents / 100.0
                    val satoshis = fiatToSatoshis(fiatAmount)
                    satoshiInput.clear()
                    satoshiInput.append(satoshis.toString())
                    // Keep fiatInput unchanged so we can restore it when switching back
                } else {
                    // Currently in satoshi mode, converting to fiat mode
                    val satoshis = currentInputStr.toLong()
                    val fiatAmount = bitcoinPriceWorker?.satoshisToFiat(satoshis) ?: 0.0
                    val fiatCents = (fiatAmount * 100).toLong()
                    fiatInput.clear()
                    fiatInput.append(fiatCents.toString())
                    // Keep satoshiInput unchanged so we can restore it when switching back
                }
            } catch (e: NumberFormatException) {
                // If conversion fails, don't change anything
            }
        }

        isUsdInputMode = !isUsdInputMode
        // Persist the input mode preference
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(KEY_INPUT_MODE, isUsdInputMode)
            .apply()
        updateDisplay(AnimationType.CURRENCY_SWITCH)
    }

    private fun onKeypadButtonClick(label: String) {
        vibrateKeypad()
        val currentInput = getCurrentInput()
        when (label) {
            "C" -> {
                // Clear both inputs when clearing
                satoshiInput.clear()
                fiatInput.clear()
            }
            "<" -> if (currentInput.isNotEmpty()) currentInput.setLength(currentInput.length - 1)
            else -> {
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
        updateDisplay(AnimationType.DIGIT_ENTRY)
    }

    private fun animateCurrencySwitch(newText: String, isUp: Boolean) {
        amountDisplay.animate().cancel()
        if (amountDisplay.alpha == 0f) {
            amountDisplay.alpha = 1f
            amountDisplay.translationY = 0f
        }
        val exitTranslation = if (isUp) -50f else 50f
        val enterStartTranslation = if (isUp) 50f else -50f
        amountDisplay.animate()
            .alpha(0f)
            .translationY(exitTranslation)
            .setDuration(150)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                amountDisplay.text = newText
                amountDisplay.translationY = enterStartTranslation
                amountDisplay.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(200)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            }
            .start()
    }

    private fun animateDigitEntry(newText: String) {
        amountDisplay.animate().cancel()
        amountDisplay.animate()
            .alpha(0.7f)
            .scaleX(0.92f)
            .scaleY(0.92f)
            .setDuration(80)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f))
            .withEndAction {
                amountDisplay.text = newText
                amountDisplay.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(200)
                    .setInterpolator(android.view.animation.OvershootInterpolator(0.8f))
                    .start()
            }
            .start()
    }

    private fun animateSecondaryCurrencySwitch(newText: String, isUp: Boolean) {
        secondaryAmountDisplay.animate().cancel()
        switchCurrencyButton.animate().cancel()
        
        if (secondaryAmountDisplay.alpha == 0f) {
            secondaryAmountDisplay.alpha = 1f
            secondaryAmountDisplay.translationY = 0f
        }
        if (switchCurrencyButton.alpha == 0f) {
            switchCurrencyButton.alpha = 1f
            switchCurrencyButton.translationY = 0f
        }
        
        val exitTranslation = if (isUp) -30f else 30f
        val enterStartTranslation = if (isUp) 30f else -30f
        
        // Animate both text and icon together
        secondaryAmountDisplay.animate()
            .alpha(0f)
            .translationY(exitTranslation)
            .setDuration(150)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                secondaryAmountDisplay.text = newText
                secondaryAmountDisplay.translationY = enterStartTranslation
                secondaryAmountDisplay.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(200)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            }
            .start()
            
        // Animate icon with same timing
        switchCurrencyButton.animate()
            .alpha(0f)
            .translationY(exitTranslation)
            .setDuration(150)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                switchCurrencyButton.translationY = enterStartTranslation
                switchCurrencyButton.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(200)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            }
            .start()
    }

    private fun formatAmount(amount: String): String = try {
        val value = if (amount.isEmpty()) 0L else amount.toLong()
        Amount(value, Amount.Currency.BTC).toString()
    } catch (_: NumberFormatException) {
        ""
    }

    /** Get the current input StringBuilder based on input mode. */
    private fun getCurrentInput(): StringBuilder {
        return if (isUsdInputMode) fiatInput else satoshiInput
    }

    /** Convert fiat amount (in currency units, not cents) to satoshis. */
    private fun fiatToSatoshis(fiatAmount: Double): Long {
        val price = bitcoinPriceWorker?.getCurrentPrice() ?: 0.0
        if (price <= 0) return 0L
        
        val btcAmount = fiatAmount / price
        return (btcAmount * 100_000_000).toLong()
    }

    private fun updateDisplay(animationType: AnimationType) {
        val currentInputStr = getCurrentInput().toString()
        var amountDisplayText: String
        var secondaryDisplayText: String
        var satsValue: Long = 0
        
        // Check if we have Bitcoin price data
        val hasBitcoinPrice = (bitcoinPriceWorker?.getCurrentPrice() ?: 0.0) > 0

        if (isUsdInputMode) {
            // Input mode: fiat, display fiat as primary, sats as secondary
            val fiatCents = if (currentInputStr.isEmpty()) 0L else currentInputStr.toLong()
            val currencyCode = CurrencyManager.getInstance(this).getCurrentCurrency()
            val currency = Amount.Currency.fromCode(currencyCode)
            
            amountDisplayText = Amount(fiatCents, currency).toString()
            
            // Convert fiat to satoshis for secondary display and requestedAmount
            val fiatAmount = fiatCents / 100.0
            satsValue = fiatToSatoshis(fiatAmount)
            secondaryDisplayText = Amount(satsValue, Amount.Currency.BTC).toString()
        } else {
            // Input mode: satoshi, display sats as primary, fiat as secondary
            satsValue = if (currentInputStr.isEmpty()) 0L else currentInputStr.toLong()
            amountDisplayText = formatAmount(currentInputStr)
            
            if (hasBitcoinPrice) {
                // Show fiat conversion with swap icon
                val fiatValue = bitcoinPriceWorker?.satoshisToFiat(satsValue) ?: 0.0
                secondaryDisplayText = bitcoinPriceWorker?.formatFiatAmount(fiatValue)
                    ?: CurrencyManager.getInstance(this).formatCurrencyAmount(0.0)
                switchCurrencyButton.visibility = View.VISIBLE
            } else {
                // No price data - just show "BTC" without swap icon
                secondaryDisplayText = "BTC"
                switchCurrencyButton.visibility = View.GONE
            }
        }

        // Update secondary amount display with animation when switching currencies
        if (animationType == AnimationType.CURRENCY_SWITCH) {
            // Animate secondary display in opposite direction of main display
            animateSecondaryCurrencySwitch(secondaryDisplayText, isUsdInputMode)
        } else {
            secondaryAmountDisplay.text = secondaryDisplayText
        }

        // Update main amount display
        if (amountDisplay.text.toString() != amountDisplayText) {
            when (animationType) {
                AnimationType.CURRENCY_SWITCH -> animateCurrencySwitch(amountDisplayText, !isUsdInputMode)
                AnimationType.DIGIT_ENTRY -> animateDigitEntry(amountDisplayText)
                AnimationType.NONE -> amountDisplay.text = amountDisplayText
            }
        }

        if (satsValue > 0) {
            // Use the main amount text which is already in the correct currency
            submitButton.text = "Charge $amountDisplayText"
            submitButton.isEnabled = true
            requestedAmount = satsValue
        } else {
            submitButton.text = "Charge"
            submitButton.isEnabled = false
            requestedAmount = 0
        }
    }

    private fun showPaymentMethodDialog(amount: Long) {
        val intent = Intent(this, PaymentRequestActivity::class.java).apply {
            putExtra(PaymentRequestActivity.EXTRA_PAYMENT_AMOUNT, amount)
            // Pass the formatted amount string so it displays in the currency the user entered
            val formattedAmount = amountDisplay.text.toString()
            putExtra(PaymentRequestActivity.EXTRA_FORMATTED_AMOUNT, formattedAmount)
        }
        startActivityForResult(intent, REQUEST_CODE_PAYMENT)
    }

    private fun showAmountRequiredError() {
        // Show error message
        errorMessage.visibility = View.VISIBLE
        
        // Shake the amount display
        shakeAmountDisplay()
        
        // Hide error message after 3 seconds
        mainHandler.postDelayed({
            errorMessage.visibility = View.GONE
        }, 3000)
    }

    private fun shakeAmountDisplay() {
        // Create a shake animation that moves left and right
        // This creates a smooth oscillating motion
        val shake = object : Animation() {
            override fun applyTransformation(interpolatedTime: Float, t: android.view.animation.Transformation) {
                // Oscillate 8 times (left-right-left-right...) with 10px amplitude
                val offset = (Math.sin(interpolatedTime * Math.PI * 8) * 10).toFloat()
                t.matrix.setTranslate(offset, 0f)
            }
        }.apply {
            duration = 400
        }
        
        amountDisplay.startAnimation(shake)
    }

    // NDEF payment (HCE) is preserved but not currently invoked in the main flow
    private fun proceedWithNdefPayment(amount: Long) {
        if (!NdefHostCardEmulationService.isHceAvailable(this)) {
            Toast.makeText(this, "Host Card Emulation is not available on this device", Toast.LENGTH_SHORT).show()
            return
        }

        val mintManager = MintManager.getInstance(this)
        val allowedMints = mintManager.getAllowedMints()
        val paymentRequest = CashuPaymentHelper.createPaymentRequest(amount, "Payment of $amount sats", allowedMints)
            ?: run {
                Toast.makeText(this, "Failed to create payment request", Toast.LENGTH_SHORT).show()
                return
            }

        val builder = AlertDialog.Builder(this, R.style.Theme_Shellshock)
        val dialogView = layoutInflater.inflate(R.layout.dialog_ndef_payment, null)
        builder.setView(dialogView)

        val amountView: TextView = dialogView.findViewById(R.id.nfc_amount_display)
        val statusText: TextView = dialogView.findViewById(R.id.nfc_status_text)
        val cancelButton: Button = dialogView.findViewById(R.id.nfc_cancel_button)

        amountView.text = formatAmount(amount.toString())
        val dialog = builder.create().apply { setCancelable(false); show() }

        statusText.text = "Initializing Host Card Emulation..."
        startService(Intent(this, NdefHostCardEmulationService::class.java))

        Handler(Looper.getMainLooper()).postDelayed({
            val service = NdefHostCardEmulationService.getInstance()
            if (service != null) {
                setupNdefPayment(service, paymentRequest, statusText, dialog, amount)
            } else {
                statusText.text = "Error: Host Card Emulation service not available"
            }
        }, 1000)

        cancelButton.setOnClickListener {
            stopHceService()
            dialog.dismiss()
            Toast.makeText(this, "Payment canceled", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupNdefPayment(
        service: NdefHostCardEmulationService,
        paymentRequest: String,
        statusText: TextView,
        dialog: AlertDialog,
        amount: Long,
    ) {
        try {
            service.setPaymentRequest(paymentRequest, amount)
            service.setPaymentCallback(object : NdefHostCardEmulationService.CashuPaymentCallback {
                override fun onCashuTokenReceived(token: String) {
                    runOnUiThread {
                        if (dialog.isShowing) dialog.dismiss()
                        handlePaymentSuccess(token)
                    }
                }

                override fun onCashuPaymentError(errorMessage: String) {
                    runOnUiThread {
                        if (dialog.isShowing) dialog.dismiss()
                        handlePaymentError("Payment failed: $errorMessage")
                    }
                }
            })
            statusText.text = "Waiting for payment...\n\nHold your phone against the paying device"
        } catch (e: Exception) {
            if (dialog.isShowing) dialog.dismiss()
            handlePaymentError("Error setting up NDEF payment: ${e.message}")
        }
    }

    private fun showRescanDialog() {
        val builder = AlertDialog.Builder(this, R.style.Theme_Shellshock)
        val dialogView = layoutInflater.inflate(R.layout.dialog_nfc_modern_simplified, null)
        builder.setView(dialogView)
        builder.setCancelable(true)
        builder.setOnCancelListener {
            savedPin = null
            waitingForRescan = false
        }
        dialogView.findViewById<Button?>(R.id.nfc_cancel_button)?.setOnClickListener {
            rescanDialog?.dismiss()
            savedPin = null
            waitingForRescan = false
            Toast.makeText(this, "Payment canceled", Toast.LENGTH_SHORT).show()
        }
        rescanDialog = builder.create().apply {
            window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            show()
        }
    }

    private fun showProcessingDialog() {
        val builder = AlertDialog.Builder(this, R.style.Theme_Shellshock)
        val dialogView = layoutInflater.inflate(R.layout.dialog_nfc_modern_simplified, null)
        builder.setView(dialogView)
        dialogView.findViewById<Button?>(R.id.nfc_cancel_button)?.visibility = View.GONE
        builder.setCancelable(false)
        processingDialog = builder.create().apply {
            window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            show()
        }
    }

    private fun vibrateSuccess() {
        vibrator?.let { v -> v.vibrate(PATTERN_SUCCESS, -1) }
    }

    private fun vibrateError() {
        vibrator?.let { v -> v.vibrate(PATTERN_ERROR, -1) }
    }

    private fun vibrateKeypad() {
        vibrator?.let { v ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                v.vibrate(android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_CLICK))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(VIBRATE_KEYPAD)
            }
        }
    }

    override fun onOperationSuccess() { runOnUiThread { vibrateSuccess() } }
    override fun onOperationError() { runOnUiThread { vibrateError() } }

    override fun onResume() {
        super.onResume()
        
        // Reapply theme when returning from settings
        applyTheme()
        
        nfcAdapter?.let { adapter ->
            val pendingIntent = PendingIntent.getActivity(
                this, 0, Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_MUTABLE,
            )
            val techList = arrayOf(arrayOf(android.nfc.tech.IsoDep::class.java.name))
            adapter.enableForegroundDispatch(this, pendingIntent, null, techList)
        }
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_PAYMENT) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                val token = data.getStringExtra(PaymentRequestActivity.RESULT_EXTRA_TOKEN)
                val amount = data.getLongExtra(PaymentRequestActivity.RESULT_EXTRA_AMOUNT, 0L)
                if (token != null && amount > 0) {
                    // Calculate what the user "entered" based on display mode
                    val (entryUnit, enteredAmount) = if (isUsdInputMode) {
                        // User was viewing in USD, so calculate the fiat cents value
                        val fiatValue = bitcoinPriceWorker?.satoshisToFiat(amount) ?: 0.0
                        val cents = (fiatValue * 100).toLong()
                        "USD" to cents
                    } else {
                        // User was viewing in sats
                        "sat" to amount
                    }
                    val bitcoinPrice = bitcoinPriceWorker?.getCurrentPrice()?.takeIf { it > 0 }
                    requestedAmount = 0
                    satoshiInput.clear()
                    fiatInput.clear()
                    updateDisplay(AnimationType.NONE)
                    playSuccessFeedback()
                    val mintUrl = extractMintUrlFromToken(token)
                    PaymentsHistoryActivity.addToHistory(this, token, amount, "sat", entryUnit, enteredAmount, bitcoinPrice, mintUrl, null)
                    val successIntent = Intent(this, PaymentReceivedActivity::class.java).apply {
                        putExtra(PaymentReceivedActivity.EXTRA_TOKEN, token)
                        putExtra(PaymentReceivedActivity.EXTRA_AMOUNT, amount)
                    }
                    startActivity(successIntent)
                } else {
                    Toast.makeText(this, "Payment completed but data was invalid", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Payment cancelled", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        stopHceService()
        bitcoinPriceWorker?.stop()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        rescanDialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        processingDialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (android.nfc.NfcAdapter.ACTION_TECH_DISCOVERED == intent.action) {
            val tag: android.nfc.Tag? = intent.getParcelableExtra(android.nfc.NfcAdapter.EXTRA_TAG)
            if (tag != null) handleNfcPayment(tag)
        }
    }

    private fun handleNfcPayment(tag: android.nfc.Tag) {
        if (requestedAmount <= 0) {
            Toast.makeText(this, "Please enter an amount first", Toast.LENGTH_SHORT).show()
            return
        }
        if (waitingForRescan && savedPin != null) {
            // TODO(shellshock-kotlin): Re-implement full PIN-based rescan flow here
            // matching the original Java ModernPOSActivity logic:
            //  - use savedPin + waitingForRescan
            //  - reconnect SatocashNfcClient
            //  - authenticatePIN(savedPin)
            //  - retry getPayment(requestedAmount, "SAT")
            Toast.makeText(this, "PIN-based rescan not supported in this build", Toast.LENGTH_SHORT).show()
            return
        }
        waitingForRescan = false
        Thread {
            try {
                val tempClient = SatocashNfcClient(tag).also { it.connect() }
                satocashClient = tempClient
                satocashWallet = SatocashWallet(satocashClient)
                satocashClient?.selectApplet(SatocashNfcClient.SATOCASH_AID)
                satocashClient?.initSecureChannel()
                try {
                    val token = satocashWallet!!.getPayment(requestedAmount, "SAT").join()
                    handlePaymentSuccess(token)
                    return@Thread
                } catch (e: RuntimeException) {
                    if (e.message?.contains("not enough funds") == true) {
                        handlePaymentError("Insufficient funds on card")
                        return@Thread
                    }
                    val cause = e.cause
                    if (cause is SatocashNfcClient.SatocashException) {
                        val statusWord = cause.sw
                        if (statusWord == SW.UNAUTHORIZED) {
                            // TODO(shellshock-kotlin): Restore PIN entry + rescan UX here
                            // Similar to TopUpActivity.processImportWithSavedPin and the
                            // original Java ModernPOSActivity:
                            //  - showPinDialog
                            //  - save PIN to savedPin
                            //  - set waitingForRescan = true
                            //  - showRescanDialog()
                            handlePaymentError("PIN-required flow not implemented in this build")
                        } else {
                            handlePaymentError("Card Error: (SW: 0x%04X)".format(statusWord))
                        }
                    } else {
                        handlePaymentError("Payment failed: ${e.message}")
                    }
                }
            } catch (e: java.io.IOException) {
                handlePaymentError("NFC Communication Error: ${e.message}")
            } catch (e: SatocashNfcClient.SatocashException) {
                handlePaymentError("Satocash Card Error: ${e.message} (SW: 0x%04X)".format(e.sw))
            } catch (e: Exception) {
                handlePaymentError("An unexpected error occurred: ${e.message}")
            } finally {
                try {
                    if (satocashClient != null) {
                        satocashClient?.close()
                        satocashClient = null
                    }
                } catch (_: java.io.IOException) {}
            }
        }.start()
    }

    private fun handlePaymentError(message: String) {
        requestedAmount = 0
        satoshiInput.clear()
        fiatInput.clear()
        resetHceService()
        mainHandler.post {
            rescanDialog?.dismiss()
            processingDialog?.dismiss()
            Toast.makeText(this, "Payment error: $message", Toast.LENGTH_LONG).show()
        }
    }

    private fun resetHceService() {
        try {
            val service = NdefHostCardEmulationService.getInstance()
            if (service != null) {
                service.clearPaymentRequest()
                service.setPaymentCallback(null)
            }
        } catch (_: Exception) {}
    }

    private fun stopHceService() {
        try {
            resetHceService()
            val service = NdefHostCardEmulationService.getInstance()
            if (service != null) {
                stopService(Intent(this, NdefHostCardEmulationService::class.java))
            }
        } catch (_: Exception) {}
    }

    private fun handlePaymentSuccess(token: String) {
        val amount = requestedAmount
        val (entryUnit, enteredAmount) = if (isUsdInputMode) {
            val price = bitcoinPriceWorker?.getCurrentPrice() ?: 0.0
            if (price > 0) {
                val fiatValue = bitcoinPriceWorker?.satoshisToFiat(amount) ?: 0.0
                "USD" to (fiatValue * 100).toLong()
            } else { "USD" to amount }
        } else { "sat" to amount }
        val bitcoinPrice = bitcoinPriceWorker?.getCurrentPrice()?.takeIf { it > 0 }
        requestedAmount = 0
        satoshiInput.clear()
        fiatInput.clear()
        playSuccessFeedback()
        val mintUrl = extractMintUrlFromToken(token)
        PaymentsHistoryActivity.addToHistory(this, token, amount, "sat", entryUnit, enteredAmount, bitcoinPrice, mintUrl, null)
        mainHandler.post {
            rescanDialog?.dismiss()
            processingDialog?.dismiss()
            updateDisplay(AnimationType.NONE)
            val successIntent = Intent(this, PaymentReceivedActivity::class.java).apply {
                putExtra(PaymentReceivedActivity.EXTRA_TOKEN, token)
                putExtra(PaymentReceivedActivity.EXTRA_AMOUNT, amount)
            }
            startActivity(successIntent)
        }
    }

    private fun playSuccessFeedback() {
        try {
            val mediaPlayer = android.media.MediaPlayer.create(this, R.raw.success_sound)
            mediaPlayer?.setOnCompletionListener { it.release() }
            mediaPlayer?.start()
        } catch (_: Exception) {}
        vibrateSuccess()
    }

    private fun showPinDialog(callback: (String?) -> Unit) {
        mainHandler.post {
            val builder = AlertDialog.Builder(this)
            builder.setTitle("Enter PIN")
            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val density = resources.displayMetrics.density
                val padding = (50 * density).toInt()
                val paddingVertical = (20 * density).toInt()
                setPadding(padding, paddingVertical, padding, paddingVertical)
            }
            val input = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                hint = "PIN"
            }
            layout.addView(input)
            val keypadLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val buttons = arrayOf(arrayOf("1", "2", "3"), arrayOf("4", "5", "6"), arrayOf("7", "8", "9"), arrayOf("", "0", "DEL"))
            for (row in buttons) {
                val rowLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f }
                }
                for (text in row) {
                    val button = Button(this).apply {
                        setText(text)
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f }
                        setOnClickListener {
                            when (text) {
                                "DEL" -> if (input.length() > 0) input.text.delete(input.length() - 1, input.length())
                                "" -> {}
                                else -> input.append(text)
                            }
                        }
                    }
                    rowLayout.addView(button)
                }
                keypadLayout.addView(rowLayout)
            }
            val buttonLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (20 * resources.displayMetrics.density).toInt() }
            }
            val cancelButton = Button(this).apply {
                text = "Cancel"
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = (8 * resources.displayMetrics.density).toInt() }
            }
            val okButton = Button(this).apply {
                text = "OK"
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = (8 * resources.displayMetrics.density).toInt() }
            }
            buttonLayout.addView(cancelButton)
            buttonLayout.addView(okButton)
            layout.addView(keypadLayout)
            layout.addView(buttonLayout)
            builder.setView(layout)
            val dialog = builder.create()
            cancelButton.setOnClickListener { dialog.cancel(); callback(null) }
            okButton.setOnClickListener { val pin = input.text.toString(); dialog.dismiss(); callback(pin) }
            dialog.setOnCancelListener { callback(null) }
            dialog.show()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_top_up -> { startActivity(Intent(this, TopUpActivity::class.java)); true }
        R.id.action_balance_check -> { startActivity(Intent(this, BalanceCheckActivity::class.java)); true }
        R.id.action_history -> { startActivity(Intent(this, PaymentsHistoryActivity::class.java)); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun showOverflowMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.overflow_menu, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_overflow_top_up -> { startActivity(Intent(this, TopUpActivity::class.java)); true }
                R.id.action_overflow_balance_check -> { startActivity(Intent(this, BalanceCheckActivity::class.java)); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun extractMintUrlFromToken(tokenString: String?): String? = try {
        if (!tokenString.isNullOrEmpty()) com.cashujdk.nut00.Token.decode(tokenString).mint else null
    } catch (_: Exception) { null }

    private fun applyTheme() {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val theme = prefs.getString("app_theme", "obsidian") ?: "obsidian"
        val isDarkMode = prefs.getBoolean(KEY_DARK_MODE, false)
        
        // Get the actual root ConstraintLayout from the content view
        val contentView = findViewById<android.view.ViewGroup>(android.R.id.content)
        val rootLayout = contentView.getChildAt(0) as? androidx.constraintlayout.widget.ConstraintLayout
        
        val isWhiteTheme = (theme == "white")
        
        val backgroundColor = when (theme) {
            "obsidian" -> android.graphics.Color.parseColor("#0B1215")
            "bitcoin_orange" -> android.graphics.Color.parseColor("#F7931A")
            "green" -> android.graphics.Color.parseColor("#00C244")
            "white" -> android.graphics.Color.parseColor("#FFFFFF")
            else -> android.graphics.Color.parseColor("#0B1215")
        }
        
        // Text color: black ONLY for white theme, white for all other themes
        // (Obsidian, Bitcoin Orange, Green should always have white text)
        val textColor = if (isWhiteTheme) {
            android.graphics.Color.parseColor("#0B1215")
        } else {
            android.graphics.Color.WHITE
        }
        
        rootLayout?.setBackgroundColor(backgroundColor)
        
        // Update all text colors
        amountDisplay.setTextColor(textColor)
        secondaryAmountDisplay.setTextColor(textColor)
        errorMessage.setTextColor(textColor)
        
        // Update currency switch icon tint
        (switchCurrencyButton as? android.widget.ImageButton)?.setColorFilter(textColor)
        
        // Update top navigation icons - white when dark mode is on with colored themes
        val topIconColor = if (isDarkMode && !isWhiteTheme) {
            android.graphics.Color.WHITE
        } else {
            textColor
        }
        
        findViewById<android.widget.ImageButton>(R.id.action_catalog)?.setColorFilter(topIconColor)
        findViewById<android.widget.ImageButton>(R.id.action_history)?.setColorFilter(topIconColor)
        findViewById<android.widget.ImageButton>(R.id.action_settings)?.setColorFilter(topIconColor)
        
        // Update keypad button colors
        val keypad = findViewById<android.widget.GridLayout>(R.id.keypad)
        for (i in 0 until keypad.childCount) {
            val button = keypad.getChildAt(i) as? android.widget.Button
            button?.setTextColor(textColor)
        }
        
        // Update status bar and navigation bar colors to match background
        window.statusBarColor = backgroundColor
        window.navigationBarColor = backgroundColor
        
        // Update status bar appearance based on theme
        val windowInsetsController = WindowInsetsControllerCompat(window, window.decorView)
        windowInsetsController.isAppearanceLightStatusBars = isWhiteTheme
        windowInsetsController.isAppearanceLightNavigationBars = isWhiteTheme
        
        // Special button handling for Obsidian and White themes
        when (theme) {
            "white" -> {
                // White theme: 100% black button with white text
                submitButton.setBackgroundResource(R.drawable.bg_button_primary_green)
                submitButton.setTextColor(android.graphics.Color.WHITE)
            }
            "obsidian" -> {
                // Obsidian theme: 100% white button with black text (white when disabled)
                submitButton.setBackgroundResource(R.drawable.bg_button_white)
                submitButton.setTextColor(resources.getColorStateList(R.color.button_text_obsidian, null))
            }
            else -> {
                // Bitcoin Orange, Green: use default opacity-based drawable
                submitButton.setBackgroundResource(R.drawable.bg_button_charge)
                submitButton.setTextColor(android.graphics.Color.WHITE)
            }
        }
    }

    companion object {
        private const val TAG = "ModernPOSActivity"
        private const val PREFS_NAME = "ShellshockPrefs"
        private const val KEY_DARK_MODE = "darkMode"
        private const val KEY_INPUT_MODE = "inputMode" // true = fiat, false = satoshi
        private const val REQUEST_CODE_PAYMENT = 1001
        private val PATTERN_SUCCESS = longArrayOf(0, 50, 100, 50)
        private val PATTERN_ERROR = longArrayOf(0, 100)
        private const val VIBRATE_KEYPAD = 20L
    }
}
