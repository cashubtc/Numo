package com.electricdreams.numo.ui.components

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.os.Vibrator
import com.electricdreams.numo.util.getVibrator
import com.electricdreams.numo.util.vibrateCompat
import android.view.View
import android.widget.Button
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.electricdreams.numo.core.util.NetworkUtils
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import com.electricdreams.numo.R
import com.electricdreams.numo.core.cashu.CashuWalletManager
import com.electricdreams.numo.core.util.MintManager
import com.electricdreams.numo.core.worker.BitcoinPriceWorker
import com.electricdreams.numo.feature.history.PaymentsHistoryActivity
import com.electricdreams.numo.feature.items.ItemSelectionActivity
import com.electricdreams.numo.feature.settings.SettingsActivity
import com.electricdreams.numo.payment.PaymentMethodHandler
import com.electricdreams.numo.payment.PaymentResultHandler
import com.electricdreams.numo.payment.NfcPaymentProcessor
import com.electricdreams.numo.ui.theme.ThemeManager

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
    private lateinit var submitButtonSpinner: ProgressBar
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
    private lateinit var mintManager: MintManager

    /** Initialize all UI components and managers */
    fun initialize() {
        initializeViews()
        initializeManagers()
        setupNavigationButtons()
        
        // Disable charge button initially, enable when wallet is ready
        submitButton.isEnabled = false
        submitButton.alpha = 0.5f
        
        // Load mint limits from preferred Lightning mint
        loadMintLimits()

        if (true) {
            activity.lifecycleScope.launch {
                CashuWalletManager.walletState.combine(NetworkUtils.observeNetworkState(activity)) { state, isNetworkAvailable ->
                    Pair(state, isNetworkAvailable)
                }.collect { (state, isNetworkAvailable) ->
                    val isReady = state == com.electricdreams.numo.core.cashu.WalletState.READY
                    val canCharge = isReady && isNetworkAvailable
                    // Make sure we only enable it if we don't have a spinner shown
                    if (submitButtonSpinner.visibility != android.view.View.VISIBLE) {
                        submitButton.isEnabled = canCharge
                        submitButton.alpha = if (canCharge) 1.0f else 0.5f
                        
                        // Rely on AmountDisplayManager to set correct text/state based on amount and wallet readiness
                        amountDisplayManager.updateDisplay(
                            satoshiInput,
                            fiatInput,
                            AmountDisplayManager.AnimationType.NONE
                        )
                    }
                }
            }
        }
    }

    private fun loadMintLimits() {
        val lightningMint = mintManager.getPreferredLightningMint()
        if (lightningMint != null) {
            activity.lifecycleScope.launch {
                // Pre-load cache for ALL allowed mints when app opens
                // This ensures every mint has valid cached data, preventing issues when switching mints
                Log.d(TAG, "Pre-loading cache for all allowed mints...")
                for (mintUrl in mintManager.getAllowedMints()) {
                    try {
                        // Use isFirstFetch=true to store valid cache, skip if already cached
                        mintManager.getMintLimits(mintUrl, activity, forceRefresh = false, isFirstFetch = true)
                        Log.d(TAG, "Pre-loaded cache for: $mintUrl")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to pre-load cache for: $mintUrl", e)
                    }
                }
                
                // Now get limits for the preferred mint
                val isStale = mintManager.needsRefresh(lightningMint)
                val limits = mintManager.getMintLimits(lightningMint, activity, forceRefresh = isStale, isFirstFetch = false)
                amountDisplayManager.setMintLimits(limits)
                
                // Update display to re-evaluate button state based on new limits
                amountDisplayManager.updateDisplay(satoshiInput, fiatInput, AmountDisplayManager.AnimationType.NONE)
            }
        }
    }
    
    /** Reload mint limits - called when returning to POS (e.g., after changing lightning mint) */
    fun reloadMintLimits() {
        Log.d(TAG, "reloadMintLimits() called")
        val lightningMint = mintManager.getPreferredLightningMint()
        Log.d(TAG, "Preferred mint: $lightningMint")
        if (lightningMint != null) {
            // Disable button while refreshing, but let AmountDisplayManager handle the text based on wallet state
            submitButton.isEnabled = false
            
            activity.lifecycleScope.launch {
                // Force refresh if stale, NOT first fetch - preserve existing cache
                // This prevents inconsistent mint responses from overwriting valid limits
                val isStale = mintManager.needsRefresh(lightningMint)
                Log.d(TAG, "Fetching mint limits with forceRefresh=\$isStale (NOT first fetch)")
                val limits = mintManager.getMintLimits(lightningMint, activity, forceRefresh = isStale, isFirstFetch = false)
                Log.d(TAG, "Got limits: $limits")
                
                // Same behavior as onCreate - always set limits
                amountDisplayManager.setMintLimits(limits)
                
                // Update display to re-evaluate button state based on new limits
                amountDisplayManager.updateDisplay(satoshiInput, fiatInput, AmountDisplayManager.AnimationType.NONE)
            }
        }
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
                    showChargeButtonSpinner()
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
        hideChargeButtonSpinner()
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

    /** Refresh the display when currency or other settings may have changed */
    fun refreshDisplay() {
        // Force a display update to reflect any currency changes
        amountDisplayManager.updateDisplay(satoshiInput, fiatInput, AmountDisplayManager.AnimationType.NONE)
    }

    /** Handle NFC payment */
    @Suppress("DEPRECATION")
    fun handleNfcPayment(tag: android.nfc.Tag) {
        nfcPaymentProcessor.handleNfcPayment(tag, amountDisplayManager.requestedAmount)
    }

    /** Handle payment result error */
    fun handlePaymentError(message: String) {
        paymentResultHandler.handlePaymentError(message) {
            resetToInputMode()
        }
    }

    /**
     * Unified success handler - plays feedback and shows success screen.
     * This is the single source of truth for all payment success handling.
     */
    private fun showPaymentSuccess(token: String, amount: Long) {
        // Play success sound
        try {
            val mediaPlayer = android.media.MediaPlayer.create(activity, R.raw.success_sound)
            mediaPlayer?.setOnCompletionListener { it.release() }
            mediaPlayer?.start()
        } catch (e: Exception) {
            android.util.Log.e("PosUiCoordinator", "Error playing success sound: ${e.message}")
        }
        
        // Vibrate
        try {
            val vibrator = activity.getVibrator()
            vibrator?.vibrateCompat(PATTERN_SUCCESS, -1)
        } catch (e: Exception) {
            android.util.Log.e("PosUiCoordinator", "Error vibrating: ${e.message}")
        }

        // Show success screen
        val successIntent = android.content.Intent(activity, com.electricdreams.numo.PaymentReceivedActivity::class.java).apply {
            putExtra(com.electricdreams.numo.PaymentReceivedActivity.EXTRA_TOKEN, token)
            putExtra(com.electricdreams.numo.PaymentReceivedActivity.EXTRA_AMOUNT, amount)
        }
        activity.startActivity(successIntent)
    }

    /** Stop services */
    fun stopServices() {
        nfcPaymentProcessor.stopHceService()
    }

    /** Get requested amount */
    fun getRequestedAmount(): Long = amountDisplayManager.requestedAmount

    companion object {
        private const val TAG = "PosUiCoordinator"
        private val PATTERN_SUCCESS = longArrayOf(0, 50, 100, 50)
    }

    private fun initializeViews() {
        amountDisplay = activity.findViewById(R.id.amount_display)
        secondaryAmountDisplay = activity.findViewById(R.id.secondary_amount_display)
        submitButton = activity.findViewById(R.id.submit_button)
        submitButtonSpinner = activity.findViewById(R.id.submit_button_spinner)
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

        // Initialize mint manager for enabling/disabling charge button based on mint availability
        mintManager = MintManager.getInstance(activity)

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
                ) { resultToken, resultAmount ->
                    // Use unified success handler
                    showPaymentSuccess(resultToken, resultAmount)
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
            if (!NetworkUtils.isNetworkAvailable(activity)) {
                android.widget.Toast.makeText(
                    activity,
                    activity.getString(R.string.pos_error_no_network_charge),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            // Do not allow charging if no mints are configured
            if (!mintManager.hasAnyMints()) {
                // Optional: show a gentle message guiding user to configure mints
                android.widget.Toast.makeText(
                    activity,
                    activity.getString(R.string.pos_error_no_mints_configured),
                    android.widget.Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }

            if (amountDisplayManager.requestedAmount > 0) {
                showChargeButtonSpinner()
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
            false
        }
        popup.show()
    }

    /** Show spinner on charge button and disable it */
    private fun showChargeButtonSpinner() {
        submitButtonSpinner.visibility = View.VISIBLE
        submitButton.text = "" // Hide button text
        submitButton.isEnabled = false
    }

    /** Hide spinner on charge button and enable it */
    fun hideChargeButtonSpinner() {
        submitButtonSpinner.visibility = View.GONE
        submitButton.text = activity.getString(R.string.pos_charge_button) // Restore button text
        // Only re-enable if the wallet is ready and network is available
        val isReady = CashuWalletManager.walletState.value == com.electricdreams.numo.core.cashu.WalletState.READY
        val isNetworkAvailable = NetworkUtils.isNetworkAvailable(activity)
        val canCharge = isReady && isNetworkAvailable
        submitButton.isEnabled = canCharge
        submitButton.alpha = if (canCharge) 1.0f else 0.5f
    }
}
