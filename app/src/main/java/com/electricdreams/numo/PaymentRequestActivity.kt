package com.electricdreams.numo
import com.electricdreams.numo.R

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.electricdreams.numo.core.data.model.PaymentHistoryEntry
import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.core.model.Amount.Currency
import com.electricdreams.numo.core.util.MintManager
import com.electricdreams.numo.core.util.SavedBasketManager
import com.electricdreams.numo.core.worker.BitcoinPriceWorker
import com.electricdreams.numo.core.util.CurrencyManager
import com.electricdreams.numo.feature.history.PaymentsHistoryActivity
import com.electricdreams.numo.feature.tips.TipSelectionActivity
import com.electricdreams.numo.ndef.CashuPaymentHelper
import com.electricdreams.numo.ndef.NdefHostCardEmulationService
import com.electricdreams.numo.payment.LightningMintHandler
import com.electricdreams.numo.payment.NostrPaymentHandler
import com.electricdreams.numo.payment.PaymentIntentFactory
import com.electricdreams.numo.payment.PaymentTabManager
import com.electricdreams.numo.ui.animation.NfcPaymentAnimationView
import com.electricdreams.numo.ui.util.QrCodeGenerator
import com.electricdreams.numo.feature.autowithdraw.AutoWithdrawManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PaymentRequestActivity : AppCompatActivity() {

    private lateinit var cashuQrImageView: ImageView
    private lateinit var lightningQrImageView: ImageView
    private lateinit var cashuQrContainer: View
    private lateinit var lightningQrContainer: View
    private lateinit var cashuTab: TextView
    private lateinit var lightningTab: TextView
    private lateinit var largeAmountDisplay: TextView
    private lateinit var convertedAmountDisplay: TextView
    private lateinit var statusText: TextView
    private lateinit var closeButton: View
    private lateinit var shareButton: View
    private lateinit var lightningLoadingSpinner: View
    private lateinit var lightningLogoCard: View
    
    // NFC Animation views
    private lateinit var nfcAnimationContainer: View
    private lateinit var nfcAnimationView: NfcPaymentAnimationView
    private lateinit var animationResultAmountText: TextView
    private lateinit var animationResultLabelText: TextView
    private lateinit var animationActionsContainer: View
    private lateinit var animationViewDetailsButton: TextView
    private lateinit var animationCloseButton: TextView
    
    // Tip-related views
    private lateinit var tipInfoText: TextView

    // HCE mode for deciding which payload to emulate (Cashu vs Lightning)
    private enum class HceMode { CASHU, LIGHTNING }
    private enum class OverlayActionMode { SUCCESS, ERROR }

    private var paymentAmount: Long = 0
    private var bitcoinPriceWorker: BitcoinPriceWorker? = null
    private var hcePaymentRequest: String? = null
    private var formattedAmountString: String = ""
    
    // Tip state (received from TipSelectionActivity)
    private var tipAmountSats: Long = 0
    private var tipPercentage: Int = 0
    private var baseAmountSats: Long = 0
    private var baseFormattedAmount: String = ""

    // Current HCE mode (defaults to Cashu)
    private var currentHceMode: HceMode = HceMode.CASHU

    // Tab manager for Cashu/Lightning tab switching
    private lateinit var tabManager: PaymentTabManager

    // Payment handlers
    private var nostrHandler: NostrPaymentHandler? = null
    private var lightningHandler: LightningMintHandler? = null
    private var lightningStarted = false

    // Lightning quote info for history
    private var lightningInvoice: String? = null
    private var lightningQuoteId: String? = null
    private var lightningMintUrl: String? = null

    // Pending payment tracking
    private var pendingPaymentId: String? = null
    private var isResumingPayment = false
    
    // Resume data for Lightning
    private var resumeLightningQuoteId: String? = null
    private var resumeLightningMintUrl: String? = null
    private var resumeLightningInvoice: String? = null

    // Resume data for Nostr
    private var resumeNostrSecretHex: String? = null
    private var resumeNostrNprofile: String? = null

    // Checkout basket data (for item-based checkouts)
    private var checkoutBasketJson: String? = null
    
    // Saved basket ID (for basket-payment association)
    private var savedBasketId: String? = null

    // Tracks whether this payment flow has already reached a terminal outcome
    private var hasTerminalOutcome: Boolean = false

    // Pending NFC animation outcome data consumed when native animation reaches terminal frame.
    private var pendingNfcSuccessToken: String? = null
    private var pendingNfcSuccessAmount: Long = 0
    private var currentOverlayActionMode: OverlayActionMode = OverlayActionMode.SUCCESS
    private var isProcessingNfcPayment = false

    private val uiScope = CoroutineScope(Dispatchers.Main)

    // NFC animation timing diagnostics
    private var nfcOverlayShownAtMs: Long = 0
    private var nfcAnimationStartedAtMs: Long = 0
    private var animationAmountBaseTranslationY: Float = 0f
    private var animationLabelBaseTranslationY: Float = 0f
    private var nfcAnimationTimeoutRunnable: Runnable? = null
    private val nfcTimeoutHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_payment_request)

        // Initialize views
        cashuQrImageView = findViewById(R.id.payment_request_qr)
        lightningQrImageView = findViewById(R.id.lightning_qr)
        cashuQrContainer = findViewById(R.id.cashu_qr_container)
        lightningQrContainer = findViewById(R.id.lightning_qr_container)
        cashuTab = findViewById(R.id.cashu_tab)
        lightningTab = findViewById(R.id.lightning_tab)
        largeAmountDisplay = findViewById(R.id.large_amount_display)
        convertedAmountDisplay = findViewById(R.id.converted_amount_display)
        statusText = findViewById(R.id.payment_status_text)
        closeButton = findViewById(R.id.close_button)
        shareButton = findViewById(R.id.share_button)
        lightningLoadingSpinner = findViewById(R.id.lightning_loading_spinner)
        lightningLogoCard = findViewById(R.id.lightning_logo_card)
        
        // NFC Animation views
        nfcAnimationContainer = findViewById(R.id.nfc_animation_container)
        nfcAnimationView = findViewById(R.id.nfc_animation_view)
        animationResultAmountText = findViewById(R.id.animation_result_amount)
        animationResultLabelText = findViewById(R.id.animation_result_label)
        animationActionsContainer = findViewById(R.id.animation_actions_container)
        animationViewDetailsButton = findViewById(R.id.animation_view_details_button)
        animationCloseButton = findViewById(R.id.animation_close_button)
        animationAmountBaseTranslationY = animationResultAmountText.translationY
        animationLabelBaseTranslationY = animationResultLabelText.translationY

        setupNfcAnimationOverlay()

        // Initialize tab manager
        tabManager = PaymentTabManager(
            cashuTab = cashuTab,
            lightningTab = lightningTab,
            cashuQrContainer = cashuQrContainer,
            lightningQrContainer = lightningQrContainer,
            cashuQrImageView = cashuQrImageView,
            lightningQrImageView = lightningQrImageView,
            resources = resources,
            theme = theme
        )

        // Set up tabs with listener
        tabManager.setup(object : PaymentTabManager.TabSelectionListener {
            override fun onLightningTabSelected() {
                Log.d(TAG, "onLightningTabSelected() called. lightningStarted=$lightningStarted, lightningInvoice=$lightningInvoice")

                // If invoice is already known, try to switch HCE now
                if (lightningInvoice != null) {
                    setHceToLightning()
                }
            }

            override fun onCashuTabSelected() {
                Log.d(TAG, "onCashuTabSelected() called. currentHceMode=$currentHceMode")
                // When user returns to Cashu tab, restore Cashu HCE payload
                setHceToCashu()
            }
        })

        // Initialize Bitcoin price worker
        bitcoinPriceWorker = BitcoinPriceWorker.getInstance(this)

        // Get payment amount from intent
        paymentAmount = intent.getLongExtra(EXTRA_PAYMENT_AMOUNT, 0)

        if (paymentAmount <= 0) {
            Log.e(TAG, "Invalid payment amount: $paymentAmount")
            Toast.makeText(this, R.string.payment_request_error_invalid_amount, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Get formatted amount string if provided, otherwise format as BTC
        formattedAmountString = intent.getStringExtra(EXTRA_FORMATTED_AMOUNT)
            ?: Amount(paymentAmount, Currency.BTC).toString()

        // Check if we're resuming a pending payment
        pendingPaymentId = intent.getStringExtra(EXTRA_RESUME_PAYMENT_ID)
        isResumingPayment = pendingPaymentId != null

        // Get resume data for Lightning if available
        resumeLightningQuoteId = intent.getStringExtra(EXTRA_LIGHTNING_QUOTE_ID)
        resumeLightningMintUrl = intent.getStringExtra(EXTRA_LIGHTNING_MINT_URL)
        resumeLightningInvoice = intent.getStringExtra(EXTRA_LIGHTNING_INVOICE)

        // Get resume data for Nostr if available
        resumeNostrSecretHex = intent.getStringExtra(EXTRA_NOSTR_SECRET_HEX)
        resumeNostrNprofile = intent.getStringExtra(EXTRA_NOSTR_NPROFILE)

        // Get checkout basket data (for item-based checkouts)
        checkoutBasketJson = intent.getStringExtra(EXTRA_CHECKOUT_BASKET_JSON)
        
        // Get saved basket ID (for basket-payment association)
        savedBasketId = intent.getStringExtra(EXTRA_SAVED_BASKET_ID)

        // Display amount (without "Pay" prefix since it's in the label above)
        largeAmountDisplay.text = formattedAmountString

        // Calculate and display converted amount
        updateConvertedAmount(formattedAmountString)

        // Read tip info from intent BEFORE creating pending payment
        // This must happen before createPendingPayment() so tip data is included
        readTipInfoFromIntent()

        // Set up buttons
        closeButton.setOnClickListener {
            Log.d(TAG, "Payment cancelled by user")
            cancelPayment()
        }

        shareButton.setOnClickListener {
            // By default, share the Cashu (Nostr) payment request; fall back to Lightning invoice
            val toShare = nostrHandler?.paymentRequest ?: lightningHandler?.currentInvoice ?: lightningInvoice
            if (toShare != null) {
                sharePaymentRequest(toShare)
            } else {
                Toast.makeText(this, R.string.payment_request_error_nothing_to_share, Toast.LENGTH_SHORT).show()
            }
        }

        // Create pending payment entry if this is a new payment (not resuming)
        // This now includes tip info since readTipInfoFromIntent() was called first
        if (!isResumingPayment) {
            createPendingPayment()
        }
        
        // Set up tip display UI (after pending payment is created)
        setupTipDisplay()

        // Initialize all payment modes (NDEF, Nostr, Lightning)
        initializePaymentRequest()

        // If resuming and we have Lightning data, auto-switch to Lightning tab
        if (isResumingPayment && resumeLightningQuoteId != null) {
            tabManager.selectLightningTab()
        }
    }

    /**
     * Read tip info from intent extras.
     * Called BEFORE createPendingPayment() so tip data is available.
     */
    private fun readTipInfoFromIntent() {
        tipAmountSats = intent.getLongExtra(TipSelectionActivity.EXTRA_TIP_AMOUNT_SATS, 0)
        tipPercentage = intent.getIntExtra(TipSelectionActivity.EXTRA_TIP_PERCENTAGE, 0)
        baseAmountSats = intent.getLongExtra(TipSelectionActivity.EXTRA_BASE_AMOUNT_SATS, 0)
        baseFormattedAmount = intent.getStringExtra(TipSelectionActivity.EXTRA_BASE_FORMATTED_AMOUNT) ?: ""
        
        if (tipAmountSats > 0) {
            Log.d(TAG, "Read tip info from intent: tipAmount=$tipAmountSats, tipPercent=$tipPercentage%, baseAmount=$baseAmountSats")
        }
    }

    private fun createPendingPayment() {
        // Determine the entry unit and entered amount
        // If tip is present, use the BASE amount (what was originally entered)
        // If no tip, parse from formattedAmountString
        val entryUnit: String
        val enteredAmount: Long
        
        if (tipAmountSats > 0 && baseAmountSats > 0) {
            // Tip is present - use base amounts for accounting
            // Parse base formatted amount to get the original entry unit
            val parsedBase = Amount.parse(baseFormattedAmount)
            if (parsedBase != null) {
                entryUnit = if (parsedBase.currency == Currency.BTC) "sat" else parsedBase.currency.name
                enteredAmount = parsedBase.value
            } else {
                // Fallback: use sats for base amount
                entryUnit = "sat"
                enteredAmount = baseAmountSats
            }
            Log.d(TAG, "Creating pending payment with tip: base=$enteredAmount $entryUnit, tip=$tipAmountSats sats, total=$paymentAmount sats")
        } else {
            // No tip - parse the formatted amount string
            val parsedAmount = Amount.parse(formattedAmountString)
            if (parsedAmount != null) {
                entryUnit = if (parsedAmount.currency == Currency.BTC) "sat" else parsedAmount.currency.name
                enteredAmount = parsedAmount.value
            } else {
                // Fallback if parsing fails (shouldn't happen with valid formatted amounts)
                entryUnit = "sat"
                enteredAmount = paymentAmount
            }
        }

        val bitcoinPrice = bitcoinPriceWorker?.getCurrentPrice()?.takeIf { it > 0 }

        pendingPaymentId = PaymentsHistoryActivity.addPendingPayment(
            context = this,
            amount = paymentAmount,
            entryUnit = entryUnit,
            enteredAmount = enteredAmount,
            bitcoinPrice = bitcoinPrice,
            paymentRequest = null, // Will be set after payment request is created
            formattedAmount = formattedAmountString,
            checkoutBasketJson = checkoutBasketJson,
            basketId = savedBasketId,
            tipAmountSats = tipAmountSats,
            tipPercentage = tipPercentage,
        )

        Log.d(TAG, "✅ CREATED PENDING PAYMENT: id=$pendingPaymentId")
        Log.d(TAG, "   💰 Total amount: $paymentAmount sats")
        Log.d(TAG, "   📊 Base amount: $enteredAmount $entryUnit")  
        Log.d(TAG, "   💸 Tip: $tipAmountSats sats ($tipPercentage%)")
        Log.d(TAG, "   🛒 Has basket: ${checkoutBasketJson != null}")
        Log.d(TAG, "   📱 Formatted: $formattedAmountString")
    }

    private fun updateConvertedAmount(formattedAmountString: String) {
        // Check if the formatted amount is BTC (satoshis) or fiat
        val isBtcAmount = formattedAmountString.startsWith("₿")

        val hasBitcoinPrice = (bitcoinPriceWorker?.getCurrentPrice() ?: 0.0) > 0

        if (!hasBitcoinPrice) {
            convertedAmountDisplay.visibility = View.GONE
            return
        }

        if (isBtcAmount) {
            // Main amount is BTC, show fiat conversion
            val fiatValue = bitcoinPriceWorker?.satoshisToFiat(paymentAmount) ?: 0.0
            if (fiatValue > 0) {
                val formattedFiat = bitcoinPriceWorker?.formatFiatAmount(fiatValue)
                    ?: CurrencyManager.getInstance(this).formatCurrencyAmount(fiatValue)
                convertedAmountDisplay.text = formattedFiat
                convertedAmountDisplay.visibility = View.VISIBLE
            } else {
                convertedAmountDisplay.visibility = View.GONE
            }
        } else {
            // Main amount is fiat, show BTC conversion
            // paymentAmount is always in satoshis, so we can use it directly
            if (paymentAmount > 0) {
                val formattedBtc = Amount(paymentAmount, Currency.BTC).toString()
                convertedAmountDisplay.text = formattedBtc
                convertedAmountDisplay.visibility = View.VISIBLE
            } else {
                convertedAmountDisplay.visibility = View.GONE
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if (item.itemId == android.R.id.home) {
            cancelPayment()
            true
        } else {
            super.onOptionsItemSelected(item)
        }
    }

    override fun onBackPressed() {
        cancelPayment()
        super.onBackPressed()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && nfcAnimationContainer.visibility == View.VISIBLE) {
            applyFullscreenForAnimationOverlay()
        }
    }

    private fun initializePaymentRequest() {
        statusText.visibility = View.VISIBLE
        statusText.text = getString(R.string.payment_request_status_preparing)

        // Get allowed mints
        val mintManager = MintManager.getInstance(this)
        val allowedMints = mintManager.getAllowedMints()
        Log.d(TAG, "Using ${allowedMints.size} allowed mints for payment request")

        // Initialize Lightning handler with preferred mint (will be started when tab is selected)
        val preferredLightningMint = mintManager.getPreferredLightningMint()
        lightningHandler = LightningMintHandler(preferredLightningMint, allowedMints, uiScope)
        startLightningMintFlow()

        // Check if NDEF is available
        val ndefAvailable = NdefHostCardEmulationService.isHceAvailable(this)

        // HCE (NDEF) PaymentRequest
        if (ndefAvailable) {
            // When "Accept payments from unknown mints" is enabled we
            // intentionally omit the mints field from the PaymentRequest for
            // HCE as well. Some wallets interpret an explicit mints list as a
            // strict requirement rather than a preference, which would
            // prevent them from paying with other mints even though the POS
            // will accept them via swap.
            val mintsForPaymentRequest =
                if (mintManager.isSwapFromUnknownMintsEnabled()) null else allowedMints

            hcePaymentRequest = CashuPaymentHelper.createPaymentRequest(
                paymentAmount,
                "Payment of $paymentAmount sats",
                mintsForPaymentRequest
            )

            if (hcePaymentRequest == null) {
                Log.e(TAG, "Failed to create payment request for HCE")
                Toast.makeText(this, R.string.payment_request_error_ndef_prepare, Toast.LENGTH_SHORT).show()
            } else {
                Log.d(TAG, "Created HCE payment request: $hcePaymentRequest")

                // Start HCE service in the background
                val serviceIntent = Intent(this, NdefHostCardEmulationService::class.java)
                startService(serviceIntent)
                setupNdefPayment()
            }
        }

        // Initialize Nostr handler and start payment flow
        nostrHandler = NostrPaymentHandler(this, allowedMints)
        startNostrPaymentFlow()

        // Lightning flow is now also started immediately (see startLightningMintFlow() call above)
    }

    private fun setHceToCashu() {
        val request = hcePaymentRequest ?: run {
            Log.w(TAG, "setHceToCashu() called but hcePaymentRequest is null")
            return
        }

        try {
            val hceService = NdefHostCardEmulationService.getInstance()
            if (hceService != null) {
                Log.d(TAG, "setHceToCashu(): Switching HCE payload to Cashu request")
                hceService.setPaymentRequest(request, paymentAmount)
                currentHceMode = HceMode.CASHU
            } else {
                Log.w(TAG, "setHceToCashu(): HCE service not available")
            }
        } catch (e: Exception) {
            Log.e(TAG, "setHceToCashu(): Error while setting HCE Cashu payload: ${e.message}", e)
        }
    }

    private fun setHceToLightning() {
        val invoice = lightningInvoice ?: run {
            Log.w(TAG, "setHceToLightning() called but lightningInvoice is null")
            return
        }
        val payload = "lightning:$invoice"

        try {
            val hceService = NdefHostCardEmulationService.getInstance()
            if (hceService != null) {
                Log.d(TAG, "setHceToLightning(): Switching HCE payload to Lightning invoice. payload=$payload")
                // Lightning mode is just a text payload; amount check is not used here
                hceService.setPaymentRequest(payload, 0L)
                currentHceMode = HceMode.LIGHTNING
            } else {
                Log.w(TAG, "setHceToLightning(): HCE service not available")
            }
        } catch (e: Exception) {
            Log.e(TAG, "setHceToLightning(): Error while setting HCE Lightning payload: ${e.message}", e)
        }
    }

    private fun startNostrPaymentFlow() {
        val handler = nostrHandler ?: return

        val callback = object : NostrPaymentHandler.Callback {
            override fun onPaymentRequestReady(paymentRequest: String) {
                try {
                    val qrBitmap = QrCodeGenerator.generate(paymentRequest, 512)
                    cashuQrImageView.setImageBitmap(qrBitmap)
                    statusText.text = getString(R.string.payment_request_status_waiting_for_payment)
                } catch (e: Exception) {
                    Log.e(TAG, "Error generating Cashu QR bitmap: ${e.message}", e)
                    statusText.text = getString(R.string.payment_request_status_error_qr)
                }
            }

            override fun onTokenReceived(token: String) {
                runOnUiThread {
                    handlePaymentSuccess(token)
                }
            }

            override fun onError(message: String) {
                Log.e(TAG, "Nostr payment error: $message")

                // Show inline status and delegate to unified failure handling
                statusText.text = getString(R.string.payment_request_status_error_generic, message)

                // Treat this as a terminal failure for this payment request
                // and show the global payment failure screen so the user can
                // explicitly retry the latest pending payment.
                handlePaymentError("Nostr payment failed: $message")
            }
        }

        if (isResumingPayment && resumeNostrSecretHex != null && resumeNostrNprofile != null) {
            // Resume with stored keys
            handler.resume(paymentAmount, resumeNostrSecretHex!!, resumeNostrNprofile!!, callback)
        } else {
            // Start fresh
            handler.start(paymentAmount, pendingPaymentId, callback)
        }
    }

    private fun startLightningMintFlow() {
        lightningStarted = true

        // Check if we're resuming with existing Lightning quote
        if (resumeLightningQuoteId != null && resumeLightningMintUrl != null && resumeLightningInvoice != null) {
            Log.d(TAG, "Resuming Lightning quote: id=$resumeLightningQuoteId")
            
            lightningHandler?.resume(
                quoteId = resumeLightningQuoteId!!,
                mintUrlStr = resumeLightningMintUrl!!,
                invoice = resumeLightningInvoice!!,
                callback = createLightningCallback()
            )
        } else {
            // Start fresh Lightning flow
            lightningHandler?.start(paymentAmount, createLightningCallback())
        }
    }

    private fun createLightningCallback(): LightningMintHandler.Callback {
        return object : LightningMintHandler.Callback {
            override fun onInvoiceReady(bolt11: String, quoteId: String, mintUrl: String) {
                // Store for history
                lightningInvoice = bolt11
                lightningQuoteId = quoteId
                lightningMintUrl = mintUrl

                // Update pending payment with Lightning info
                pendingPaymentId?.let { paymentId ->
                    PaymentsHistoryActivity.updatePendingWithLightningInfo(
                        context = this@PaymentRequestActivity,
                        paymentId = paymentId,
                        lightningInvoice = bolt11,
                        lightningQuoteId = quoteId,
                        lightningMintUrl = mintUrl,
                    )
                }

                try {
                    val qrBitmap = QrCodeGenerator.generate(bolt11, 512)
                    lightningQrImageView.setImageBitmap(qrBitmap)
                    // Hide loading spinner and show the bolt icon
                    lightningLoadingSpinner.visibility = View.GONE
                    lightningLogoCard.visibility = View.VISIBLE
                } catch (e: Exception) {
                    Log.e(TAG, "Error generating Lightning QR bitmap: ${e.message}", e)
                    // Still hide spinner on error
                    lightningLoadingSpinner.visibility = View.GONE
                }

                // If Lightning tab is currently visible, switch HCE payload to Lightning
                if (tabManager.isLightningTabSelected()) {
                    Log.d(TAG, "onInvoiceReady(): Lightning tab is selected, calling setHceToLightning()")
                    setHceToLightning()
                }
            }

            override fun onPaymentSuccess() {
                handleLightningPaymentSuccess()
            }

            override fun onError(message: String) {
                // Do not immediately fail the whole payment; NFC or Nostr may still succeed.
                // Only surface a toast if Lightning tab is currently active.
                if (tabManager.isLightningTabSelected()) {
                    Toast.makeText(
                        this@PaymentRequestActivity,
                        getString(R.string.payment_request_lightning_error_failed, message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun setupNdefPayment() {
        val request = hcePaymentRequest ?: return

        // Match original behavior: slight delay before configuring service
        Handler(Looper.getMainLooper()).postDelayed({
            val hceService = NdefHostCardEmulationService.getInstance()
            if (hceService != null) {
                Log.d(TAG, "Setting up NDEF payment with HCE service")

                // Set the payment request to the HCE service with expected amount (Cashu by default)
                setHceToCashu()

                // Set up callback for when a token is received or an error occurs
                hceService.setPaymentCallback(object : NdefHostCardEmulationService.CashuPaymentCallback {
                    override fun onCashuTokenReceived(token: String) {
                        // Raw Cashu token received over NFC. Delegate full
                        // validation, swap-to-Lightning-mint (if needed),
                        // and redemption to CashuPaymentHelper.
                        uiScope.launch {
                            // Check if we are already processing a payment to avoid double-processing
                            if (isProcessingNfcPayment) {
                                Log.d(TAG, "NFC token received but ignored - already processing a payment")
                                return@launch
                            }
                            
                            // Mark as processing immediately to lock out subsequent NFC reads
                            isProcessingNfcPayment = true

                            // Cancel the NFC safety timeout immediately as we have received data.
                            // The subsequent processing (swap/redemption) may take longer than
                            // the NFC timeout allows, but that is a network operation, not an NFC one.
                            cancelNfcSafetyTimeout()
                            Log.d(TAG, "NFC token received, cancelled safety timeout")

                            try {
                                val paymentId = pendingPaymentId
                                val paymentContext = com.electricdreams.numo.payment.SwapToLightningMintManager.PaymentContext(
                                    paymentId = paymentId,
                                    amountSats = paymentAmount,
                                )

                                val mintManager = MintManager.getInstance(this@PaymentRequestActivity)
                                val allowedMints = mintManager.getAllowedMints()

                                val redeemedToken = CashuPaymentHelper.redeemTokenWithSwap(
                                    appContext = this@PaymentRequestActivity,
                                    tokenString = token,
                                    expectedAmount = paymentAmount,
                                    allowedMints = allowedMints,
                                    paymentContext = paymentContext,
                                )

                                // If redeemedToken is non-empty, it's a Cashu
                                // payment; if empty, it was fulfilled via
                                // Lightning swap.
                                withContext(Dispatchers.Main) {
                                    if (redeemedToken.isNotEmpty()) {
                                        handlePaymentSuccess(redeemedToken)
                                    } else {
                                        handleLightningPaymentSuccess()
                                    }
                                }
                            } catch (e: CashuPaymentHelper.RedemptionException) {
                                val msg = e.message ?: "Unknown redemption error"
                                Log.e(TAG, "Error in NDEF payment redemption: $msg", e)
                                withContext(Dispatchers.Main) {
                                    handlePaymentError("NDEF Payment failed: $msg")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Unexpected error in NDEF payment callback: ${e.message}", e)
                                withContext(Dispatchers.Main) {
                                    handlePaymentError("NDEF Payment failed: ${e.message}")
                                }
                            }
                        }
                    }

                    override fun onCashuPaymentError(errorMessage: String) {
                        runOnUiThread {
                            Log.e(TAG, "NDEF Payment error callback: $errorMessage")
                            handlePaymentError("NDEF Payment failed: $errorMessage")
                        }
                    }

                    override fun onNfcReadingStarted() {
                        runOnUiThread {
                            if (isProcessingNfcPayment || hasTerminalOutcome) {
                                Log.d(TAG, "NFC reading started ignored - already processing or done")
                                return@runOnUiThread
                            }
                            Log.d(TAG, "NFC reading started - showing animation overlay")
                            showNfcAnimationOverlay()
                        }
                    }

                    override fun onNfcReadingStopped() {
                        runOnUiThread {
                            Log.w(TAG, "NFC reading stopped callback received")
                            if (hasTerminalOutcome) {
                                Log.d(TAG, "NFC reading stopped ignored - terminal outcome already set")
                                return@runOnUiThread
                            }

                            Log.d(TAG, "NFC reading stopped - keeping animation overlay active")
                        }
                    }
                })

                Log.d(TAG, "NDEF payment service ready")
            }
        }, 1000)
    }

    private fun handlePaymentSuccess(token: String) {
        // Only process the first terminal outcome (success or failure). Late
        // callbacks from Nostr/HCE after we've already completed this payment
        // should be ignored so we don't show a failure screen after success.
        if (!beginTerminalOutcome("cashu_success")) return

        Log.d(TAG, "Payment successful! Token: $token")
        cancelNfcSafetyTimeout()

        statusText.visibility = View.VISIBLE
        statusText.text = getString(R.string.payment_request_status_success)

        // Extract mint URL from token
        val mintUrl = try {
            com.cashujdk.nut00.Token.decode(token).mint
        } catch (e: Exception) {
            null
        }

        // Update pending payment to completed (Cashu payment path)
        pendingPaymentId?.let { paymentId ->
            PaymentsHistoryActivity.completePendingPayment(
                context = this,
                paymentId = paymentId,
                token = token,
                paymentType = PaymentHistoryEntry.TYPE_CASHU,
                mintUrl = mintUrl,
            )
        }

        val resultIntent = Intent().apply {
            putExtra(RESULT_EXTRA_TOKEN, token)
            putExtra(RESULT_EXTRA_AMOUNT, paymentAmount)
        }
        setResult(Activity.RESULT_OK, resultIntent)

        showPaymentSuccess(token, paymentAmount)
    }

    /**
     * Lightning payments do not produce a Cashu token in this flow.
     * We signal success to the caller with an empty token string so that
     * history can record the payment (amount, date, etc.) but leave the
     * token field effectively blank.
     */
    private fun handleLightningPaymentSuccess() {
        // Guard against late callbacks so we don't surface a failure screen
        // after a successful Lightning payment has already been processed.
        if (!beginTerminalOutcome("lightning_success")) return

        Log.d(TAG, "Lightning payment successful (no Cashu token)")
        cancelNfcSafetyTimeout()

        statusText.visibility = View.VISIBLE
        statusText.text = getString(R.string.payment_request_status_success)

        // Update pending payment to completed with Lightning info
        pendingPaymentId?.let { paymentId ->
            PaymentsHistoryActivity.completePendingPayment(
                context = this,
                paymentId = paymentId,
                token = "",
                paymentType = PaymentHistoryEntry.TYPE_LIGHTNING,
                mintUrl = lightningMintUrl,
                lightningInvoice = lightningInvoice,
                lightningQuoteId = lightningQuoteId,
                lightningMintUrl = lightningMintUrl,
            )
        }

        val resultIntent = Intent().apply {
            putExtra(RESULT_EXTRA_TOKEN, "")
            putExtra(RESULT_EXTRA_AMOUNT, paymentAmount)
        }
        setResult(Activity.RESULT_OK, resultIntent)

        showPaymentSuccess("", paymentAmount)
    }

    /**
     * Mark the payment flow as having reached a terminal outcome
     * (success, failure, or user cancellation).
     *
     * Only the first caller wins; any subsequent attempts (for example, late
     * error callbacks from Nostr or HCE after a successful payment) will be
     * ignored to prevent showing a failure screen after success.
     *
     * @param reason Short description used for logging why the terminal
     * outcome is being set.
     * @return true if this is the first terminal outcome and should be
     * handled; false if a terminal outcome has already been processed.
     */
    private fun beginTerminalOutcome(reason: String): Boolean {
        if (hasTerminalOutcome) {
            Log.w(TAG, "Ignoring terminal outcome after completion. reason=$reason")
            return false
        }
        hasTerminalOutcome = true
        return true
    }

    private fun handlePaymentError(errorMessage: String) {
        // If we've already processed a terminal outcome (e.g. a successful
        // payment), ignore late errors so we don't show the failure screen
        // on top of a genuine success.
        if (!beginTerminalOutcome("error: $errorMessage")) return

        Log.e(TAG, "Payment error: $errorMessage")
        cancelNfcSafetyTimeout()

        statusText.visibility = View.VISIBLE
        statusText.text = getString(R.string.payment_request_status_failed, errorMessage)
        setResult(Activity.RESULT_CANCELED)

        if (nfcAnimationContainer.visibility == View.VISIBLE) {
            showNfcAnimationError(errorMessage)
            return
        }

        Toast.makeText(
            this,
            getString(R.string.payment_request_status_failed, errorMessage),
            Toast.LENGTH_LONG,
        ).show()

        // Navigate to the global payment failure screen, which will allow
        // the user to try the latest pending entry again.
        startActivity(Intent(this, PaymentFailureActivity::class.java))

        // Clean up payment resources and finish this Activity.
        cleanupAndFinish()
    }

    private fun cancelPayment() {
        Log.d(TAG, "Payment cancelled")

        // Note: We don't cancel the pending payment here - user might want to resume it later
        // Only cancel if explicitly requested or if it's an error

        // Treat user cancellation as a terminal outcome for this Activity so
        // any late error callbacks from background flows are ignored.
        hasTerminalOutcome = true

        setResult(Activity.RESULT_CANCELED)
        cleanupAndFinish()
    }

    private fun cleanupAndFinish() {
        // Once cleanup starts, this payment flow is effectively over. This is
        // a safety net for any paths that might reach cleanup without having
        // called [beginTerminalOutcome] explicitly.
        hasTerminalOutcome = true
        cancelNfcSafetyTimeout()

        // Stop Nostr handler
        nostrHandler?.stop()
        nostrHandler = null

        // Stop Lightning handler
        lightningHandler?.cancel()
        lightningHandler = null

        // Clean up HCE service
        try {
            val hceService = NdefHostCardEmulationService.getInstance()
            if (hceService != null) {
                Log.d(TAG, "Cleaning up HCE service")
                hceService.clearPaymentRequest()
                hceService.setPaymentCallback(null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up HCE service: ${e.message}", e)
        }

        nfcAnimationView.reset()
        nfcAnimationContainer.visibility = View.GONE
        resetResultTextViews()
        resetResultActionButtons()
        restoreSystemBarsAfterAnimation()

        finish()
    }

    override fun onDestroy() {
        nfcAnimationView.reset()
        
        // onDestroy can be invoked as part of a normal lifecycle (e.g. when
        // the system reclaims the Activity). Avoid calling finish() again
        // from here; simply ensure resources are cleaned up if they haven't
        // been already.
        cleanupAndFinish()
        super.onDestroy()
    }

    private fun sharePaymentRequest(paymentRequest: String) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, paymentRequest)
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.payment_request_share_chooser_title)))
    }

    /**
     * Set up tip display UI.
     * Tip info was already read from intent in readTipInfoFromIntent().
     * This just sets up the visual display.
     */
    private fun setupTipDisplay() {
        tipInfoText = findViewById(R.id.tip_info_text)
        
        // If we have tip info, show it below the converted amount
        if (tipAmountSats > 0) {
            val tipAmount = Amount(tipAmountSats, Currency.BTC)
            val tipAmountStr = tipAmount.toString()
            val tipText = if (tipPercentage > 0) {
                getString(R.string.payment_request_tip_info_with_percentage, tipAmountStr, tipPercentage)
            } else {
                getString(R.string.payment_request_tip_info_no_percentage, tipAmountStr)
            }
            tipInfoText.text = tipText
            tipInfoText.visibility = View.VISIBLE
            
            Log.d(TAG, "Displaying tip info: $tipAmountSats sats ($tipPercentage%)")
        } else {
            tipInfoText.visibility = View.GONE
        }
    }

    /**
     * Mark the saved basket as paid and move it to archive.
     * Called when payment is successfully completed.
     */
    private fun markBasketAsPaid() {
        val basketId = savedBasketId ?: return
        val paymentId = pendingPaymentId ?: return
        
        try {
            val savedBasketManager = SavedBasketManager.getInstance(this)
            val archivedBasket = savedBasketManager.markBasketAsPaid(basketId, paymentId)
            if (archivedBasket != null) {
                Log.d(TAG, "📦 Basket archived: ${archivedBasket.id} with payment $paymentId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error archiving basket: ${e.message}", e)
        }
    }

    /**
     * Trigger post-payment operations (basket archiving + auto-withdrawal).
     * This is extracted so it can be called from both the normal flow and the NFC animation flow.
     * This ensures auto-withdrawal logic is consistent across all payment paths.
     */
    private fun triggerPostPaymentOperations(token: String) {
        // Archive the basket now that payment is complete
        markBasketAsPaid()
        
        // Check for auto-withdrawal after successful payment (runs in background, survives activity destruction)
        AutoWithdrawManager.getInstance(this).onPaymentReceived(token, lightningMintUrl)
    }

    /**
     * Unified success handler for all payment types.
     * Always renders the native success overlay so NFC and non-NFC success paths stay consistent.
     */
    private fun showPaymentSuccess(token: String, amount: Long) {
        if (nfcAnimationContainer.visibility != View.VISIBLE) {
            nfcOverlayShownAtMs = SystemClock.elapsedRealtime()
            Log.d(TAG, "overlay_shown_ms=$nfcOverlayShownAtMs")
            applyFullscreenForAnimationOverlay()
            nfcAnimationContainer.visibility = View.VISIBLE
            nfcAnimationView.reset()
            resetResultTextViews()
            resetResultActionButtons()
            ViewCompat.requestApplyInsets(nfcAnimationContainer)
        } else {
            applyFullscreenForAnimationOverlay()
        }
        pendingNfcSuccessToken = token
        pendingNfcSuccessAmount = amount
        showNfcAnimationSuccess(formattedAmountString)
    }

    // ============================================================
    // NFC Animation Methods
    // ============================================================

    private fun setupNfcAnimationOverlay() {
        animationCloseButton.setOnClickListener {
            animateSuccessScreenOut()
        }
        animationViewDetailsButton.setOnClickListener {
            onOverlaySecondaryActionPressed()
        }

        nfcAnimationView.setOnResultDisplayedListener { success ->
            runOnUiThread {
                onNfcAnimationResultDisplayed(success)
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(nfcAnimationContainer) { _, insets ->
            adjustAnimationActionsBottomMargin(insets)
            insets
        }

        Log.d(TAG, "webview_ready_ms=0 (native renderer)")
    }
    
    /**
     * Elegant fade-out animation when closing the terminal result screen.
     */
    private fun animateSuccessScreenOut() {
        // Disable the button to prevent multiple taps
        animationCloseButton.isEnabled = false
        animationViewDetailsButton.isEnabled = false
        
        // Clean up and finish with fade transition
        cleanupAndFinishWithFade()
    }
    
    /**
     * Cleanup and finish with fade animation.
     * Does NOT exit full-screen mode so the terminal result screen stays full during the fade.
     */
    private fun cleanupAndFinishWithFade() {
        cancelNfcSafetyTimeout()

        // Stop Nostr handler
        nostrHandler?.stop()
        nostrHandler = null

        // Stop Lightning handler
        lightningHandler?.cancel()
        lightningHandler = null

        // Clean up HCE service
        try {
            val hceService = NdefHostCardEmulationService.getInstance()
            if (hceService != null) {
                Log.d(TAG, "Cleaning up HCE service")
                hceService.clearPaymentRequest()
                hceService.setPaymentCallback(null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up HCE service: ${e.message}", e)
        }

        restoreSystemBarsAfterAnimation()
        finish()
        
        // Fade out the success screen, fade in the home screen
        // Must be called immediately after finish() to take effect
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
    }

    private fun showNfcAnimationOverlay() {
        nfcOverlayShownAtMs = SystemClock.elapsedRealtime()
        Log.d(TAG, "overlay_shown_ms=$nfcOverlayShownAtMs")

        applyFullscreenForAnimationOverlay()
        
        nfcAnimationContainer.visibility = View.VISIBLE
        resetResultTextViews()
        resetResultActionButtons()
        pendingNfcSuccessToken = null
        pendingNfcSuccessAmount = 0

        nfcAnimationView.reset()
        nfcAnimationView.startReading()
        nfcAnimationStartedAtMs = SystemClock.elapsedRealtime()
        Log.d(TAG, "animation_started_ms=${nfcAnimationStartedAtMs - nfcOverlayShownAtMs}")
        startNfcSafetyTimeout()
        ViewCompat.requestApplyInsets(nfcAnimationContainer)
    }

    private fun startNfcSafetyTimeout() {
        cancelNfcSafetyTimeout()

        nfcAnimationTimeoutRunnable = Runnable {
            if (hasTerminalOutcome || nfcAnimationContainer.visibility != View.VISIBLE) {
                return@Runnable
            }
            Log.e(TAG, "NFC safety timeout triggered - payment did not reach terminal state")
            handlePaymentError("Payment failed. Please try again.")
        }

        nfcTimeoutHandler.postDelayed(nfcAnimationTimeoutRunnable!!, NFC_READ_TIMEOUT_MS)
    }

    private fun cancelNfcSafetyTimeout() {
        nfcAnimationTimeoutRunnable?.let { nfcTimeoutHandler.removeCallbacks(it) }
        nfcAnimationTimeoutRunnable = null
    }

    private fun showNfcAnimationSuccess(amountText: String) {
        if (nfcAnimationContainer.visibility != View.VISIBLE) return

        currentOverlayActionMode = OverlayActionMode.SUCCESS
        animationResultAmountText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 56f)
        animationResultLabelText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
        animationResultLabelText.maxLines = 1
        animationResultAmountText.text = amountText
        animationResultLabelText.text = getString(R.string.transaction_detail_type_payment_received)
        nfcAnimationView.showSuccess(amountText)

        // Play success sound and vibration
        playNfcSuccessFeedback()
    }

    private fun playNfcSuccessFeedback() {
        // Play success sound
        try {
            val mediaPlayer = android.media.MediaPlayer.create(this, R.raw.success_sound)
            mediaPlayer?.setOnCompletionListener { it.release() }
            mediaPlayer?.start()
        } catch (_: Exception) {}
        
        // Vibrate
        val vibrator = getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator?
        vibrator?.vibrate(longArrayOf(0, 50, 100, 50), -1)
    }

    private fun showNfcAnimationError(message: String) {
        if (nfcAnimationContainer.visibility != View.VISIBLE) return

        currentOverlayActionMode = OverlayActionMode.ERROR
        animationResultLabelText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
        animationResultLabelText.maxLines = 2
        animationResultAmountText.text = ""
        animationResultLabelText.text = getString(R.string.payment_failure_title)
        nfcAnimationView.showError(message)
    }

    private fun onNfcAnimationResultDisplayed(success: Boolean) {
        val elapsedMs = if (nfcOverlayShownAtMs > 0) {
            SystemClock.elapsedRealtime() - nfcOverlayShownAtMs
        } else {
            0L
        }
        Log.d(TAG, "success_rendered_ms=$elapsedMs")

        val mode = if (success) OverlayActionMode.SUCCESS else OverlayActionMode.ERROR
        animateResultTextIn(showAmount = success)
        showResultActionsAnimated(mode)

        if (success && pendingNfcSuccessToken != null) {
            val token = pendingNfcSuccessToken!!
            pendingNfcSuccessToken = null
            pendingNfcSuccessAmount = 0

            // Trigger auto-withdrawal and basket archiving (same as showPaymentSuccess does)
            // but don't show PaymentReceivedActivity since we're already showing animation
            triggerPostPaymentOperations(token)
        }
    }

    private fun showResultActionsAnimated(mode: OverlayActionMode) {
        applyFullscreenForAnimationOverlay()
        ViewCompat.requestApplyInsets(nfcAnimationContainer)

        currentOverlayActionMode = mode
        animationViewDetailsButton.text = getString(
            if (mode == OverlayActionMode.SUCCESS) {
                R.string.payment_received_button_view_details
            } else {
                R.string.payment_failure_button_try_again
            }
        )
        animationCloseButton.text = getString(
            if (mode == OverlayActionMode.SUCCESS) {
                R.string.payment_request_animation_close
            } else {
                R.string.payment_failure_button_close
            }
        )

        animationViewDetailsButton.visibility = View.VISIBLE
        animationViewDetailsButton.isEnabled = true
        animationCloseButton.visibility = View.VISIBLE
        animationCloseButton.isEnabled = true

        // Start from invisible and below
        animationActionsContainer.alpha = 0f
        animationActionsContainer.translationY = 60f
        animationActionsContainer.visibility = View.VISIBLE

        // Animate in with fade + slide up
        animationActionsContainer.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(400)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    private fun animateResultTextIn(showAmount: Boolean) {
        val amountStartTranslation = animationAmountBaseTranslationY - dpToPx(16f).toFloat()
        val labelStartTranslation = animationLabelBaseTranslationY - dpToPx(12f).toFloat()

        if (showAmount) {
            animationResultAmountText.visibility = View.VISIBLE
            animationResultAmountText.alpha = 0f
            animationResultAmountText.translationY = amountStartTranslation
        } else {
            animationResultAmountText.visibility = View.GONE
        }

        animationResultLabelText.visibility = View.VISIBLE
        animationResultLabelText.alpha = 0f
        animationResultLabelText.translationY = labelStartTranslation

        val animators = mutableListOf<android.animation.Animator>().apply {
            if (showAmount) {
                add(ObjectAnimator.ofFloat(animationResultAmountText, View.ALPHA, 0f, 1f))
                add(
                    ObjectAnimator.ofFloat(
                        animationResultAmountText,
                        View.TRANSLATION_Y,
                        amountStartTranslation,
                        animationAmountBaseTranslationY,
                    ),
                )
            }
            add(ObjectAnimator.ofFloat(animationResultLabelText, View.ALPHA, 0f, 1f))
            add(
                ObjectAnimator.ofFloat(
                    animationResultLabelText,
                    View.TRANSLATION_Y,
                    labelStartTranslation,
                    animationLabelBaseTranslationY,
                ),
            )
        }

        AnimatorSet().apply {
            if (showAmount) {
                startDelay = 120L
            }
            duration = 320L
            interpolator = android.view.animation.DecelerateInterpolator()
            playTogether(animators)
            start()
        }
    }

    private fun resetResultTextViews() {
        animationResultAmountText.visibility = View.GONE
        animationResultAmountText.alpha = 0f
        animationResultAmountText.translationY = animationAmountBaseTranslationY
        animationResultAmountText.text = ""

        animationResultLabelText.visibility = View.GONE
        animationResultLabelText.alpha = 0f
        animationResultLabelText.translationY = animationLabelBaseTranslationY
        animationResultLabelText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
        animationResultLabelText.maxLines = 3
        animationResultLabelText.text = ""
    }

    private fun formatErrorLabel(message: String): String {
        return if (message.contains(". ")) {
            message.replaceFirst(". ", ".\n")
        } else {
            message
        }
    }

    private fun resetResultActionButtons() {
        currentOverlayActionMode = OverlayActionMode.SUCCESS
        animationActionsContainer.visibility = View.GONE
        animationActionsContainer.alpha = 0f
        animationActionsContainer.translationY = 0f
        animationViewDetailsButton.visibility = View.GONE
        animationViewDetailsButton.isEnabled = false
        animationViewDetailsButton.text = getString(R.string.payment_received_button_view_details)
        animationCloseButton.visibility = View.GONE
        animationCloseButton.isEnabled = true
        animationCloseButton.text = getString(R.string.payment_request_animation_close)
    }

    private fun applyFullscreenForAnimationOverlay() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun restoreSystemBarsAfterAnimation() {
        WindowInsetsControllerCompat(window, window.decorView).show(
            WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars()
        )
        WindowCompat.setDecorFitsSystemWindows(window, true)
    }

    private fun adjustAnimationActionsBottomMargin(insets: WindowInsetsCompat) {
        val systemInsets = insets.getInsetsIgnoringVisibility(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        val minBottomSpacingPx = dpToPx(72f)
        val targetBottomMargin = maxOf(minBottomSpacingPx, systemInsets.bottom + dpToPx(24f))

        val layoutParams = animationActionsContainer.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (layoutParams.bottomMargin != targetBottomMargin) {
            layoutParams.bottomMargin = targetBottomMargin
            animationActionsContainer.layoutParams = layoutParams
        }
    }

    private fun onOverlaySecondaryActionPressed() {
        if (currentOverlayActionMode == OverlayActionMode.ERROR) {
            retryLatestPendingPayment()
        } else {
            openLatestTransactionDetails()
        }
    }

    private fun retryLatestPendingPayment() {
        val history = PaymentsHistoryActivity.getPaymentHistory(this)
        val latestPending: PaymentHistoryEntry? = history
            .filter { it.isPending() }
            .maxByOrNull { it.date.time }

        if (latestPending == null) {
            Toast.makeText(this, R.string.payment_failure_error_no_pending, Toast.LENGTH_SHORT).show()
            return
        }

        startActivity(PaymentIntentFactory.createResumePaymentIntent(this, latestPending))
        cleanupAndFinish()
    }

    private fun openLatestTransactionDetails() {
        val history = PaymentsHistoryActivity.getPaymentHistory(this)
        val entry = history.lastOrNull()

        if (entry == null) {
            Toast.makeText(this, R.string.payment_received_error_no_details, Toast.LENGTH_SHORT).show()
            return
        }

        startActivity(
            PaymentIntentFactory.createTransactionDetailIntent(
                context = this,
                entry = entry,
                position = history.size - 1,
            ),
        )
    }

    private fun dpToPx(dp: Float): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    companion object {
        private const val TAG = "PaymentRequestActivity"
        private const val NFC_READ_TIMEOUT_MS = 5_000L



        const val EXTRA_PAYMENT_AMOUNT = "payment_amount"
        const val EXTRA_FORMATTED_AMOUNT = "formatted_amount"
        const val RESULT_EXTRA_TOKEN = "payment_token"
        const val RESULT_EXTRA_AMOUNT = "payment_amount"

        // Extras for resuming pending payments
        const val EXTRA_RESUME_PAYMENT_ID = "resume_payment_id"
        const val EXTRA_LIGHTNING_QUOTE_ID = "lightning_quote_id"
        const val EXTRA_LIGHTNING_MINT_URL = "lightning_mint_url"
        const val EXTRA_LIGHTNING_INVOICE = "lightning_invoice"
        const val EXTRA_NOSTR_SECRET_HEX = "nostr_secret_hex"
        const val EXTRA_NOSTR_NPROFILE = "nostr_nprofile"

        // Extra for checkout basket data
        const val EXTRA_CHECKOUT_BASKET_JSON = "checkout_basket_json"
        
        // Extra for saved basket ID
        const val EXTRA_SAVED_BASKET_ID = "saved_basket_id"
    }
}
