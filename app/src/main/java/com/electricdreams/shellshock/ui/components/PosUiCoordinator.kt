package com.electricdreams.shellshock.ui.components

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import com.electricdreams.shellshock.R
import com.electricdreams.shellshock.core.worker.BitcoinPriceWorker
import com.electricdreams.shellshock.feature.history.PaymentsHistoryActivity
import com.electricdreams.shellshock.feature.items.ItemSelectionActivity
import com.electricdreams.shellshock.feature.settings.SettingsActivity
import com.electricdreams.shellshock.payment.PaymentMethodHandler
import com.electricdreams.shellshock.payment.PaymentResultHandler
import com.electricdreams.shellshock.payment.NfcPaymentProcessor
import com.electricdreams.shellshock.ui.theme.ThemeManager

/**
 * Coordinates all UI managers and handles main POS interface logic.
 */
class PosUiCoordinator(
    private val activity: AppCompatActivity,
    private val bitcoinPriceWorker: BitcoinPriceWorker?
) {

    // UI Components
    private lateinit var amountDisplay: TextView
    private lateinit var secondaryAmountDisplay: TextView
    private lateinit var submitButton: Button
    private lateinit var switchCurrencyButton: View
    private lateinit var inputModeContainer: ConstraintLayout
    private lateinit var errorMessage: TextView

    // Input state
    private val satoshiInput = StringBuilder()
    private val fiatInput = StringBuilder()
    private val mainHandler = Handler(Looper.getMainLooper())

    // Manager instances
    private lateinit var amountDisplayManager: AmountDisplayManager
    private lateinit var keypadManager: KeypadManager
    private lateinit var paymentMethodHandler: PaymentMethodHandler
    private lateinit var paymentResultHandler: PaymentResultHandler
    private lateinit var themeManager: ThemeManager
    private lateinit var nfcPaymentProcessor: NfcPaymentProcessor

    /** Initialize all UI components and managers */
    fun initialize() {
        initializeViews()
        initializeManagers()
        setupNavigationButtons()
    }

    /** Handle initial payment amount from basket */
    fun handleInitialPaymentAmount(paymentAmount: Long) {
        resetToInputMode()

        if (paymentAmount > 0) {
            satoshiInput.clear()
            satoshiInput.append(paymentAmount.toString())
            fiatInput.clear()
            amountDisplayManager.updateDisplay(satoshiInput, fiatInput, AmountDisplayManager.AnimationType.NONE)

            Handler(Looper.getMainLooper()).postDelayed({
                if (submitButton.isEnabled) {
                    Log.d("PosUiCoordinator", "Auto-initiating payment flow for basket checkout with amount: $paymentAmount")
                    val formattedAmount = amountDisplay.text.toString()
                    paymentMethodHandler.showPaymentMethodDialog(amountDisplayManager.requestedAmount, formattedAmount)
                }
            }, 500)
        } else {
            amountDisplayManager.updateDisplay(satoshiInput, fiatInput, AmountDisplayManager.AnimationType.NONE)
        }
    }

    /** Reset to input mode */
    fun resetToInputMode() {
        inputModeContainer.visibility = View.VISIBLE
        nfcPaymentProcessor.dismissDialogs()
        satoshiInput.clear()
        fiatInput.clear()
        amountDisplayManager.resetRequestedAmount()
        amountDisplayManager.updateDisplay(satoshiInput, fiatInput, AmountDisplayManager.AnimationType.NONE)
    }

    /** Show amount required error */
    fun showAmountRequiredError() {
        errorMessage.visibility = View.VISIBLE
        amountDisplayManager.shakeAmountDisplay()
        
        mainHandler.postDelayed({
            errorMessage.visibility = View.GONE
        }, 3000)
    }

    /** Apply theme to all components */
    fun applyTheme() {
        themeManager.applyTheme(amountDisplay, secondaryAmountDisplay, errorMessage, switchCurrencyButton, submitButton)
    }

    /** Handle NFC payment */
    fun handleNfcPayment(tag: android.nfc.Tag) {
        nfcPaymentProcessor.handleNfcPayment(tag, amountDisplayManager.requestedAmount)
    }

    /** Handle payment result success */
    fun handlePaymentSuccess(token: String, amount: Long) {
        paymentResultHandler.handlePaymentSuccess(
            token, 
            amount, 
            amountDisplayManager.isUsdInputMode
        ) {
            resetToInputMode()
        }
    }

    /** Handle payment result error */
    fun handlePaymentError(message: String) {
        paymentResultHandler.handlePaymentError(message) {
            resetToInputMode()
        }
    }

    /** Stop services */
    fun stopServices() {
        nfcPaymentProcessor.stopHceService()
    }

    /** Get requested amount */
    fun getRequestedAmount(): Long = amountDisplayManager.requestedAmount

    private fun initializeViews() {
        amountDisplay = activity.findViewById(R.id.amount_display)
        secondaryAmountDisplay = activity.findViewById(R.id.secondary_amount_display)
        submitButton = activity.findViewById(R.id.submit_button)
        errorMessage = activity.findViewById(R.id.error_message)
        switchCurrencyButton = activity.findViewById(R.id.currency_switch_button)
        inputModeContainer = activity.findViewById(R.id.input_mode_container)
        
        // Initialize currency switch button translationY to 2dp
        val iconOffsetPx = 2f * activity.resources.displayMetrics.density
        switchCurrencyButton.translationY = iconOffsetPx
    }

    private fun initializeManagers() {
        // Initialize theme manager and apply theme
        themeManager = ThemeManager(activity)
        themeManager.applyTheme(amountDisplay, secondaryAmountDisplay, errorMessage, switchCurrencyButton, submitButton)

        // Initialize amount display manager
        amountDisplayManager = AmountDisplayManager(
            activity, amountDisplay, secondaryAmountDisplay, switchCurrencyButton, submitButton, bitcoinPriceWorker
        )
        amountDisplayManager.initializeInputMode()

        // Initialize keypad manager
        val keypad: GridLayout = activity.findViewById(R.id.keypad)
        keypadManager = KeypadManager(activity, keypad) { label ->
            keypadManager.handleKeypadInput(label, satoshiInput, fiatInput, amountDisplayManager.isUsdInputMode)
            amountDisplayManager.updateDisplay(satoshiInput, fiatInput, AmountDisplayManager.AnimationType.DIGIT_ENTRY)
        }

        // Initialize payment handlers
        paymentMethodHandler = PaymentMethodHandler(activity)
        paymentResultHandler = PaymentResultHandler(activity, bitcoinPriceWorker)
        
        // Initialize NFC processor
        nfcPaymentProcessor = NfcPaymentProcessor(
            activity,
            onPaymentSuccess = { token ->
                paymentResultHandler.handlePaymentSuccess(
                    token, 
                    amountDisplayManager.requestedAmount, 
                    amountDisplayManager.isUsdInputMode
                ) {
                    resetToInputMode()
                }
            },
            onPaymentError = { message ->
                paymentResultHandler.handlePaymentError(message) {
                    resetToInputMode()
                }
            }
        )
    }

    private fun setupNavigationButtons() {
        // Set up currency toggle
        val secondaryAmountContainer = activity.findViewById<View>(R.id.secondary_amount_container)
        secondaryAmountContainer.setOnClickListener { 
            if (amountDisplayManager.toggleInputMode(satoshiInput, fiatInput)) {
                amountDisplayManager.updateDisplay(satoshiInput, fiatInput, AmountDisplayManager.AnimationType.CURRENCY_SWITCH)
            }
        }

        // Navigation buttons
        activity.findViewById<ImageButton>(R.id.action_more_options).setOnClickListener { 
            showOverflowMenu(it) 
        }
        activity.findViewById<ImageButton>(R.id.action_history).setOnClickListener {
            activity.startActivity(Intent(activity, PaymentsHistoryActivity::class.java))
        }
        activity.findViewById<ImageButton>(R.id.action_catalog).setOnClickListener {
            activity.startActivity(Intent(activity, ItemSelectionActivity::class.java))
        }
        activity.findViewById<ImageButton>(R.id.action_settings).setOnClickListener {
            activity.startActivity(Intent(activity, SettingsActivity::class.java))
        }

        // Submit button
        submitButton.setOnClickListener {
            if (amountDisplayManager.requestedAmount > 0) {
                val formattedAmount = amountDisplay.text.toString()
                paymentMethodHandler.showPaymentMethodDialog(amountDisplayManager.requestedAmount, formattedAmount)
            } else {
                showAmountRequiredError()
            }
        }
    }

    private fun showOverflowMenu(anchor: View) {
        val popup = androidx.appcompat.widget.PopupMenu(activity, anchor)
        popup.menuInflater.inflate(R.menu.overflow_menu, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_overflow_top_up -> { 
                    activity.startActivity(Intent(activity, com.electricdreams.shellshock.TopUpActivity::class.java))
                    true 
                }
                R.id.action_overflow_balance_check -> { 
                    activity.startActivity(Intent(activity, com.electricdreams.shellshock.BalanceCheckActivity::class.java))
                    true 
                }
                else -> false
            }
        }
        popup.show()
    }
}
