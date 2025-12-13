package com.electricdreams.numo
import com.electricdreams.numo.R

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
import com.electricdreams.numo.payment.PaymentTabManager
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
    private lateinit var nfcAnimationWebView: WebView
    private lateinit var animationCloseButton: TextView
    
    // Tip-related views
    private lateinit var tipInfoText: TextView

    // HCE mode for deciding which payload to emulate (Cashu vs Lightning)
    private enum class HceMode { CASHU, LIGHTNING }

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

    // Pending NFC animation success data (to call showPaymentSuccess after animation completes)
    private var pendingNfcSuccessToken: String? = null
    private var pendingNfcSuccessAmount: Long = 0

    private val uiScope = CoroutineScope(Dispatchers.Main)
    
    // NFC Animation state
    private var webViewReady = false
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
        nfcAnimationWebView = findViewById(R.id.nfc_animation_webview)
        animationCloseButton = findViewById(R.id.animation_close_button)
        
        // Setup WebView for NFC animation
        setupAnimationWebView()

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

                // Start lightning quote flow once when tab first selected
                if (!lightningStarted) {
                    startLightningMintFlow()
                } else if (lightningInvoice != null) {
                    // If invoice is already known, try to switch HCE now
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

        // Lightning flow is started only when user switches to Lightning tab
        // (see TabSelectionListener.onLightningTabSelected())
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
                            Log.d(TAG, "NFC reading started - showing animation overlay")
                            showNfcAnimationOverlay()
                        }
                    }

                    override fun onNfcReadingStopped() {
                        // Don't hide overlay - let the animation complete
                        runOnUiThread {
                            Log.d(TAG, "NFC reading stopped - animation continues")
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
        
        // Cancel the safety timeout
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

        // Check if NFC animation is visible - if so, show success animation
        if (nfcAnimationContainer.visibility == View.VISIBLE) {
            // Store data for post-animation processing
            pendingNfcSuccessToken = token
            pendingNfcSuccessAmount = paymentAmount
            showNfcAnimationSuccess(formattedAmountString)
        } else {
            // Non-NFC payment (Nostr/QR) - use normal success flow
            showPaymentSuccess(token, paymentAmount)
        }
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
        
        // Cancel the safety timeout
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

        // Check if NFC animation is visible - if so, show success animation
        // This can happen when Lightning payment comes via NFC swap path
        if (nfcAnimationContainer.visibility == View.VISIBLE) {
            // Store data for post-animation processing (empty token for Lightning)
            pendingNfcSuccessToken = ""
            pendingNfcSuccessAmount = paymentAmount
            showNfcAnimationSuccess(formattedAmountString)
        } else {
            // Non-NFC payment - use normal success flow
            showPaymentSuccess("", paymentAmount)
        }
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

    private fun showPaymentReceivedActivity(token: String) {
        val intent = Intent(this, PaymentReceivedActivity::class.java).apply {
            putExtra(PaymentReceivedActivity.EXTRA_TOKEN, token)
            putExtra(PaymentReceivedActivity.EXTRA_AMOUNT, paymentAmount)
        }
        startActivity(intent)
        cleanupAndFinish()
    }

    private fun handlePaymentError(errorMessage: String) {
        // If we've already processed a terminal outcome (e.g. a successful
        // payment), ignore late errors so we don't show the failure screen
        // on top of a genuine success.
        if (!beginTerminalOutcome("error: $errorMessage")) return

        Log.e(TAG, "Payment error: $errorMessage")

        statusText.visibility = View.VISIBLE
        statusText.text = getString(R.string.payment_request_status_failed, errorMessage)
        Toast.makeText(
            this,
            getString(R.string.payment_request_status_failed, errorMessage),
            Toast.LENGTH_LONG,
        ).show()

        setResult(Activity.RESULT_CANCELED)

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

        finish()
    }

    override fun onDestroy() {
        // Clean up WebView to prevent memory leaks
        nfcAnimationWebView.apply {
            stopLoading()
            loadUrl("about:blank")
            removeAllViews()
            destroy()
        }
        
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
     * Unified success handler - plays feedback, triggers auto-withdrawal check, and shows success screen.
     * This is the single source of truth for payment success handling.
     */
    private fun showPaymentSuccess(token: String, amount: Long) {
        // Trigger post-payment operations (basket archiving + auto-withdrawal)
        triggerPostPaymentOperations(token)
        
        // Play success sound
        try {
            val mediaPlayer = android.media.MediaPlayer.create(this, R.raw.success_sound)
            mediaPlayer?.setOnCompletionListener { it.release() }
            mediaPlayer?.start()
        } catch (e: Exception) {
            Log.e(TAG, "Error playing success sound: ${e.message}")
        }
        
        // Vibrate
        try {
            val vibrator = getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator?
            vibrator?.vibrate(PATTERN_SUCCESS, -1)
        } catch (e: Exception) {
            Log.e(TAG, "Error vibrating: ${e.message}")
        }

        // Show success screen
        showPaymentReceivedActivity(token)
    }

    // ============================================================
    // NFC Animation Methods
    // ============================================================

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupAnimationWebView() {
        nfcAnimationWebView.settings.apply {
            javaScriptEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        
        // Make WebView background transparent
        nfcAnimationWebView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        
        // Add JavaScript interface for communication
        nfcAnimationWebView.addJavascriptInterface(AnimationBridge(), "Android")
        
        nfcAnimationWebView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                webViewReady = true
                Log.d(TAG, "WebView animation page loaded")
            }
        }
        
        // Load the animation HTML
        nfcAnimationWebView.loadUrl("file:///android_asset/nfc_animation.html")
        
        // Setup close button - animate out then close
        animationCloseButton.setOnClickListener {
            animateSuccessScreenOut()
        }
    }
    
    /**
     * Elegant fade-out animation when closing the success screen.
     */
    private fun animateSuccessScreenOut() {
        // Disable the button to prevent multiple taps
        animationCloseButton.isEnabled = false
        
        // Clean up and finish with fade transition
        cleanupAndFinishWithFade()
    }
    
    /**
     * Cleanup and finish with fade animation.
     * Does NOT exit full-screen mode so the green success screen stays full during the fade.
     */
    private fun cleanupAndFinishWithFade() {
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

        finish()
        
        // Fade out the success screen, fade in the home screen
        // Must be called immediately after finish() to take effect
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
    }

    private fun showNfcAnimationOverlay() {
        // Make activity full screen for animation
        makeFullScreen()
        
        nfcAnimationContainer.visibility = View.VISIBLE
        animationCloseButton.visibility = View.GONE
        
        // Start safety timeout - if no result in 10 seconds, show error
        startNfcSafetyTimeout()
        
        // Start the animation
        if (webViewReady) {
            nfcAnimationWebView.evaluateJavascript("startAnimation()", null)
        }
    }
    
    private fun startNfcSafetyTimeout() {
        // Cancel any existing timeout
        cancelNfcSafetyTimeout()
        
        nfcAnimationTimeoutRunnable = Runnable {
            if (nfcAnimationContainer.visibility == View.VISIBLE) {
                Log.e(TAG, "NFC safety timeout triggered - payment took too long")
                handlePaymentError("Payment failed. Please try again.")
            }
        }
        nfcTimeoutHandler.postDelayed(nfcAnimationTimeoutRunnable!!, 10000) // 10 seconds
    }
    
    private fun cancelNfcSafetyTimeout() {
        nfcAnimationTimeoutRunnable?.let { 
            nfcTimeoutHandler.removeCallbacks(it) 
        }
        nfcAnimationTimeoutRunnable = null
    }
    
    private fun makeFullScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
    }
    
    private fun exitFullScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.show(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
    }

    private fun showNfcAnimationSuccess(amountText: String) {
        if (webViewReady) {
            val escapedAmount = amountText.replace("'", "\\'")
            nfcAnimationWebView.evaluateJavascript("showSuccess('$escapedAmount')", null)
            
            // Play success sound and vibration
            playNfcSuccessFeedback()
        }
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
        if (webViewReady) {
            val escapedMessage = message.replace("'", "\\'")
            nfcAnimationWebView.evaluateJavascript("showError('$escapedMessage')", null)
        }
    }

    /** JavaScript interface for communication from WebView */
    inner class AnimationBridge {
        @JavascriptInterface
        fun onAnimationComplete(success: Boolean) {
            runOnUiThread {
                Log.d(TAG, "Animation complete, success: $success")
                showCloseButtonAnimated()
                
                // Trigger post-payment operations after animation completes
                if (success && pendingNfcSuccessToken != null) {
                    val token = pendingNfcSuccessToken!!
                    pendingNfcSuccessToken = null
                    pendingNfcSuccessAmount = 0
                    
                    // Trigger auto-withdrawal and basket archiving (same as showPaymentSuccess does)
                    // but don't show PaymentReceivedActivity since we're already showing animation
                    triggerPostPaymentOperations(token)
                }
            }
        }
    }

    private fun showCloseButtonAnimated() {
        // Start from invisible and below
        animationCloseButton.alpha = 0f
        animationCloseButton.translationY = 60f
        animationCloseButton.visibility = View.VISIBLE
        
        // Animate in with fade + slide up
        animationCloseButton.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(400)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    companion object {
        private const val TAG = "PaymentRequestActivity"
        private val PATTERN_SUCCESS = longArrayOf(0, 50, 100, 50)



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
