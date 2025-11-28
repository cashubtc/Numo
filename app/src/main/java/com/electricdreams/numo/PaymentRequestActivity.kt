package com.electricdreams.numo
import com.electricdreams.numo.R

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.electricdreams.numo.core.data.model.PaymentHistoryEntry
import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.core.model.Amount.Currency
import com.electricdreams.numo.core.util.MintManager
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

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
    private lateinit var nfcReadingOverlay: View
    private lateinit var lightningLoadingSpinner: View
    private lateinit var lightningLogoCard: View
    
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

    private val uiScope = CoroutineScope(Dispatchers.Main)

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
        nfcReadingOverlay = findViewById(R.id.nfc_reading_overlay)

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
            hcePaymentRequest = CashuPaymentHelper.createPaymentRequest(
                paymentAmount,
                "Payment of $paymentAmount sats",
                allowedMints
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
                statusText.text = getString(R.string.payment_request_status_error_generic, message)
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
                        runOnUiThread {
                            try {
                                handlePaymentSuccess(token)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error in NDEF payment callback: ${e.message}", e)
                                handlePaymentError("NDEF Payment failed: ${e.message}")
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
                            Log.d(TAG, "NFC reading started - showing overlay")
                            nfcReadingOverlay.visibility = View.VISIBLE
                        }
                    }

                    override fun onNfcReadingStopped() {
                        runOnUiThread {
                            Log.d(TAG, "NFC reading stopped - hiding overlay")
                            nfcReadingOverlay.visibility = View.GONE
                        }
                    }
                })

                Log.d(TAG, "NDEF payment service ready")
            }
        }, 1000)
    }

    private fun handlePaymentSuccess(token: String) {
        Log.d(TAG, "Payment successful! Token: $token")

        statusText.visibility = View.VISIBLE
        statusText.text = getString(R.string.payment_request_status_success)

        // Extract mint URL from token
        val mintUrl = try {
            com.cashujdk.nut00.Token.decode(token).mint
        } catch (e: Exception) {
            null
        }

        // Update pending payment to completed
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

        // Use unified success handler
        showPaymentSuccess(token, paymentAmount)
    }

    /**
     * Lightning payments do not produce a Cashu token in this flow.
     * We signal success to the caller with an empty token string so that
     * history can record the payment (amount, date, etc.) but leave the
     * token field effectively blank.
     */
    private fun handleLightningPaymentSuccess() {
        Log.d(TAG, "Lightning payment successful (no Cashu token)")

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

        // Use unified success handler
        showPaymentSuccess("", paymentAmount)
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
        Log.e(TAG, "Payment error: $errorMessage")

        statusText.visibility = View.VISIBLE
        statusText.text = getString(R.string.payment_request_status_failed, errorMessage)
        Toast.makeText(this, getString(R.string.payment_request_status_failed, errorMessage), Toast.LENGTH_LONG).show()

        setResult(Activity.RESULT_CANCELED)

        Handler(Looper.getMainLooper()).postDelayed({
            cleanupAndFinish()
        }, 3000)
    }

    private fun cancelPayment() {
        Log.d(TAG, "Payment cancelled")

        // Note: We don't cancel the pending payment here - user might want to resume it later
        // Only cancel if explicitly requested or if it's an error

        setResult(Activity.RESULT_CANCELED)
        cleanupAndFinish()
    }

    private fun cleanupAndFinish() {
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
     * Unified success handler - plays feedback and shows success screen.
     * This is the single source of truth for payment success handling.
     */
    private fun showPaymentSuccess(token: String, amount: Long) {
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
    }
}
