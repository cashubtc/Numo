package com.electricdreams.numo

import android.animation.LayoutTransition
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.ComponentName
import android.content.res.Configuration
import android.graphics.Color
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.os.Build
import android.os.Bundle
import com.electricdreams.numo.util.getVibrator
import com.electricdreams.numo.util.vibrateCompat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import com.electricdreams.numo.core.dev.WalletLogger
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.electricdreams.numo.core.data.model.PaymentHistoryEntry
import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.core.model.Amount.Currency
import com.electricdreams.numo.core.util.MintManager
import com.electricdreams.numo.core.util.MintLimitChecker
import com.electricdreams.numo.core.util.SavedBasketManager
import com.electricdreams.numo.core.worker.BitcoinPriceWorker
import com.electricdreams.numo.core.util.CurrencyManager
import com.electricdreams.numo.feature.history.PaymentsHistoryActivity
import com.electricdreams.numo.feature.tips.TipSelectionActivity
import com.electricdreams.numo.ndef.CashuPaymentHelper
import com.electricdreams.numo.ndef.NdefHostCardEmulationService
import com.electricdreams.numo.payment.PaymentErrorMessages
import com.electricdreams.numo.payment.PaymentErrorReason
import com.electricdreams.numo.payment.LightningMintHandler
import com.electricdreams.numo.payment.NostrPaymentHandler
import com.electricdreams.numo.payment.PaymentIntentFactory
import com.electricdreams.numo.payment.PaymentTabManager
import com.electricdreams.numo.payment.PaymentWebhookDispatcher
import com.electricdreams.numo.ui.animation.NfcPaymentAnimationView
import com.electricdreams.numo.ui.animation.ReducedMotion
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.electricdreams.numo.ui.util.QrCodeGenerator
import com.electricdreams.numo.feature.autowithdraw.AutoWithdrawManager
import com.electricdreams.numo.feature.settings.DeveloperPrefs
import com.electricdreams.numo.core.payment.BtcPayQrCodeBuilder
import com.electricdreams.numo.core.payment.IPaymentService
import com.electricdreams.numo.core.payment.PaymentServiceFactory
import com.electricdreams.numo.core.payment.PaymentState
import com.electricdreams.numo.core.payment.impl.BTCPayPaymentService
import com.electricdreams.numo.core.wallet.WalletError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PaymentRequestActivity : AppCompatActivity() {

    private lateinit var unifiedQrImageView: ImageView
    private lateinit var cashuQrImageView: ImageView
    private lateinit var lightningQrImageView: ImageView
    private lateinit var unifiedQrContainer: View
    private lateinit var cashuQrContainer: View
    private lateinit var lightningQrContainer: View
    private lateinit var unifiedTab: android.widget.LinearLayout
    private lateinit var cashuTab: android.widget.LinearLayout
    private lateinit var lightningTab: android.widget.LinearLayout
    private lateinit var unifiedTabText: TextView
    private lateinit var cashuTabText: TextView
    private lateinit var lightningTabText: TextView
    private lateinit var unifiedTabIcon: TextView
    private lateinit var cashuTabIcon: ImageView
    private lateinit var lightningTabIcon: ImageView
    private lateinit var largeAmountDisplay: TextView
    private lateinit var convertedAmountDisplay: TextView
    private lateinit var statusText: TextView
    private lateinit var closeButton: View
    private lateinit var shareButton: View
    private lateinit var lightningLoadingSpinner: View
    private lateinit var unifiedLoadingSpinner: View
    private lateinit var cashuLoadingSpinner: View
    private lateinit var lightningLogoCard: View
    private lateinit var cashuLogoCard: View
    
    // Payment overlay views
    private lateinit var nfcAnimationContainer: View
    private lateinit var nfcAnimationView: NfcPaymentAnimationView
    private lateinit var nfcOverlayColumn: View
    private lateinit var nfcIndicatorSlot: View
    private lateinit var nfcLoader: CircularProgressIndicator
    private lateinit var nfcHintText: TextView
    private lateinit var animationResultAmountText: TextView
    private lateinit var animationResultLabelText: TextView
    private lateinit var nfcResultReasonText: TextView
    private lateinit var nfcResultReassuranceText: TextView
    private lateinit var animationActionsContainer: View
    private lateinit var animationViewDetailsButton: TextView
    private lateinit var animationCloseButton: TextView
    
    // Tip-related views
    private lateinit var tipInfoText: TextView

    // HCE mode for deciding which payload to emulate
    private enum class HceMode { UNIFIED, CASHU, LIGHTNING }
    private enum class OverlayActionMode { SUCCESS, ERROR }

    private var paymentAmount: Long = 0
    private var bitcoinPriceWorker: BitcoinPriceWorker? = null
    private var hcePaymentRequest: String? = null
    private var hcePaymentRequestBech32: String? = null
    private var formattedAmountString: String = ""
    
    // Tip state (received from TipSelectionActivity)
    private var tipAmountSats: Long = 0
    private var tipPercentage: Int = 0
    private var baseAmountSats: Long = 0
    private var baseFormattedAmount: String = ""

    // Current HCE mode (defaults to Unified)
    private var currentHceMode: HceMode = HceMode.UNIFIED

    // Tab manager for Unified/Cashu/Lightning tab switching
    private lateinit var tabManager: PaymentTabManager

    // Payment service abstraction (Local CDK or BTCPay)
    private lateinit var paymentService: IPaymentService

    // Payment handlers
    private var nostrHandler: NostrPaymentHandler? = null
    private var lightningHandler: LightningMintHandler? = null
    private var lightningStarted = false
    private var isBolt11Supported = true

    // BTCPay payment tracking
    private var btcPayPaymentId: String? = null
    private var btcPayCashuPR: String? = null
    private var btcPayCashuPRBech32: String? = null
    private var btcPayPollingJob: Job? = null
    private var btcPayInvoiceCreatedAt: Long = 0L

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

    private var hcePaymentCallback: NdefHostCardEmulationService.CashuPaymentCallback? = null
    private var nfcSetupRunnable: Runnable? = null
    private val nfcSetupHandler = Handler(Looper.getMainLooper())

    private val uiScope = CoroutineScope(Dispatchers.Main)

    // NFC animation timing diagnostics
    private var nfcOverlayShownAtMs: Long = 0
    private var nfcAnimationStartedAtMs: Long = 0
    private var pendingResultReveal: Runnable? = null
    private var amountColorAnimator: ValueAnimator? = null
    private var overlayOnSuccessColor = false
    private var showFailureReassurance = false
    private var nfcAnimationTimeoutRunnable: Runnable? = null
    private val nfcTimeoutHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_payment_request)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }

        // Apply window insets to handle edge-to-edge correctly without squishing the NFC overlay.
        // The root itself is not padded — individual chrome views have their margins adjusted
        // so the NFC animation container stays full-bleed edge-to-edge.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.payment_request_root)) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

            val density = resources.displayMetrics.density
            val topMarginPx = (16 * density).toInt()
            val bottomMarginPx = (24 * density).toInt()

            findViewById<View>(R.id.close_button).layoutParams =
                (findViewById<View>(R.id.close_button).layoutParams as MarginLayoutParams).apply {
                    topMargin = insets.top + topMarginPx
                }

            findViewById<View>(R.id.share_button).layoutParams =
                (findViewById<View>(R.id.share_button).layoutParams as MarginLayoutParams).apply {
                    topMargin = insets.top + topMarginPx
                }

            val switchContainer = findViewById<View>(R.id.lightning_cashu_switch_container)
            if (switchContainer != null) {
                switchContainer.layoutParams =
                    (switchContainer.layoutParams as MarginLayoutParams).apply {
                        bottomMargin = insets.bottom + bottomMarginPx
                    }
            }

            windowInsets
        }

        // Initialize views
        unifiedQrImageView = findViewById(R.id.unified_qr)
        cashuQrImageView = findViewById(R.id.payment_request_qr)
        lightningQrImageView = findViewById(R.id.lightning_qr)
        unifiedQrContainer = findViewById(R.id.unified_qr_container)
        cashuQrContainer = findViewById(R.id.cashu_qr_container)
        lightningQrContainer = findViewById(R.id.lightning_qr_container)
        unifiedTab = findViewById(R.id.unified_tab)
        cashuTab = findViewById(R.id.cashu_tab)
        lightningTab = findViewById(R.id.lightning_tab)
        unifiedTabText = findViewById(R.id.unified_tab_text)
        cashuTabText = findViewById(R.id.cashu_tab_text)
        lightningTabText = findViewById(R.id.lightning_tab_text)
        unifiedTabIcon = findViewById(R.id.unified_tab_icon)
        cashuTabIcon = findViewById(R.id.cashu_tab_icon)
        lightningTabIcon = findViewById(R.id.lightning_tab_icon)
        largeAmountDisplay = findViewById(R.id.large_amount_display)
        convertedAmountDisplay = findViewById(R.id.converted_amount_display)
        statusText = findViewById(R.id.payment_status_text)
        closeButton = findViewById(R.id.close_button)
        shareButton = findViewById(R.id.share_button)
        lightningLoadingSpinner = findViewById(R.id.lightning_loading_spinner)
        unifiedLoadingSpinner = findViewById(R.id.unified_loading_spinner)
        cashuLoadingSpinner = findViewById(R.id.cashu_loading_spinner)
        lightningLogoCard = findViewById(R.id.lightning_logo_card)
        cashuLogoCard = findViewById(R.id.cashu_logo_card)

        // NFC Animation views
        nfcAnimationContainer = findViewById(R.id.nfc_animation_container)
        nfcAnimationView = findViewById(R.id.nfc_animation_view)
        nfcOverlayColumn = findViewById(R.id.nfc_overlay_column)
        nfcIndicatorSlot = findViewById(R.id.nfc_indicator_slot)
        nfcLoader = findViewById(R.id.nfc_loader)
        nfcHintText = findViewById(R.id.nfc_hint)
        animationResultAmountText = findViewById(R.id.animation_result_amount)
        animationResultLabelText = findViewById(R.id.animation_result_label)
        nfcResultReasonText = findViewById(R.id.nfc_result_reason)
        nfcResultReassuranceText = findViewById(R.id.nfc_result_reassurance)
        animationActionsContainer = findViewById(R.id.animation_actions_container)
        animationViewDetailsButton = findViewById(R.id.animation_view_details_button)
        animationCloseButton = findViewById(R.id.animation_close_button)

        setupNfcAnimationOverlay()

        // Initialize tab manager
        tabManager = PaymentTabManager(
            unifiedTab = unifiedTab,
            cashuTab = cashuTab,
            lightningTab = lightningTab,
            unifiedTabText = unifiedTabText,
            cashuTabText = cashuTabText,
            lightningTabText = lightningTabText,
            unifiedTabIcon = unifiedTabIcon,
            cashuTabIcon = cashuTabIcon,
            lightningTabIcon = lightningTabIcon,
            unifiedQrContainer = unifiedQrContainer,
            cashuQrContainer = cashuQrContainer,
            lightningQrContainer = lightningQrContainer,
            unifiedQrImageView = unifiedQrImageView,
            unifiedLoadingSpinner = unifiedLoadingSpinner,
            lightningLoadingSpinner = lightningLoadingSpinner,
            cashuLoadingSpinner = cashuLoadingSpinner,
            cashuQrImageView = cashuQrImageView,
            lightningQrImageView = lightningQrImageView,
            resources = resources,
            theme = theme
        )

        // Set up tabs with listener
        tabManager.setup(object : PaymentTabManager.TabSelectionListener {
            override fun onTabSelected(tab: PaymentTabManager.PaymentTab) {
                Log.d(TAG, "onTabSelected() called. tab=$tab, lightningStarted=$lightningStarted, lightningInvoice=$lightningInvoice")
                when (tab) {
                    PaymentTabManager.PaymentTab.UNIFIED -> {
                        if (!lightningStarted && DeveloperPrefs.isLightningInvoiceDelayed(this@PaymentRequestActivity) && isBolt11Supported) {
                            startLightningMintFlow()
                        }
                        setHceToUnified()
                    }
                    PaymentTabManager.PaymentTab.LIGHTNING -> {
                        if (!lightningStarted && DeveloperPrefs.isLightningInvoiceDelayed(this@PaymentRequestActivity) && isBolt11Supported) {
                            startLightningMintFlow()
                        }
                        if (lightningInvoice != null) {
                            setHceToLightning()
                        }
                    }
                    PaymentTabManager.PaymentTab.CASHU -> {
                        setHceToCashu()
                    }
                }
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
            val currentTab = tabManager.getCurrentTab()
            val toShare = when (currentTab) {
                PaymentTabManager.PaymentTab.LIGHTNING -> lightningHandler?.currentInvoice ?: lightningInvoice
                PaymentTabManager.PaymentTab.CASHU -> nostrHandler?.paymentRequest ?: btcPayCashuPR ?: hcePaymentRequest
                PaymentTabManager.PaymentTab.UNIFIED -> {
                    val creq = nostrHandler?.paymentRequestBech32
                    val lnbc = lightningHandler?.currentInvoice ?: lightningInvoice
                    if (creq != null || lnbc != null) {
                        org.cashudevkit.createBip321Uri(creq, lnbc, null)
                    } else null
                }
            }
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

        // If resuming a local Lightning payment, auto-switch to Lightning tab.
        // BTCPay resume uses resumeLightningQuoteId for the invoice ID — don't switch tab for it.
        if (isResumingPayment && resumeLightningQuoteId != null && paymentService !is BTCPayPaymentService) {
            tabManager.selectTab(PaymentTabManager.PaymentTab.LIGHTNING)
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
        
        // Get current preferred currency to help resolve ambiguous symbols (like "kr" for SEK/NOK)
        val currentCurrencyCode = CurrencyManager.getInstance(this).getCurrentCurrency()
        val currentCurrency = Amount.Currency.fromCode(currentCurrencyCode)
        
        if (tipAmountSats > 0 && baseAmountSats > 0) {
            // Tip is present - use base amounts for accounting
            // Parse base formatted amount to get the original entry unit
            val parsedBase = Amount.parse(baseFormattedAmount, currentCurrency)
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
            val parsedAmount = Amount.parse(formattedAmountString, currentCurrency)
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
        val preferredUnit = MintManager.getInstance(this).getPreferredUnit()
        val isCustomUnit = preferredUnit.lowercase() != "sat"
        
        if (isCustomUnit) {
            convertedAmountDisplay.visibility = View.GONE
            return
        }

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

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (hasTerminalOutcome && currentOverlayActionMode == OverlayActionMode.SUCCESS) {
            animateSuccessScreenOut()
            return
        }
        cancelPayment()
        super.onBackPressed()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && nfcAnimationContainer.visibility == View.VISIBLE) {
            applyFullscreenForAnimationOverlay()
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            val nfcAdapter = NfcAdapter.getDefaultAdapter(this)
            if (nfcAdapter != null) {
                val cardEmulation = CardEmulation.getInstance(nfcAdapter)
                val componentName = ComponentName(this, com.electricdreams.numo.ndef.NdefHostCardEmulationService::class.java)
                cardEmulation.setPreferredService(this, componentName)
                Log.d(TAG, "setPreferredService to NdefHostCardEmulationService")
                
                // Mute the active polling loop to prevent Apple Pay popups on iPhones.
                // NOTE: enableReaderMode disables HCE, so we can only suppress polling on Android 15+.
                if (android.os.Build.VERSION.SDK_INT >= 35) {
                    nfcAdapter.setDiscoveryTechnology(
                        this,
                        NfcAdapter.FLAG_READER_DISABLE,
                        NfcAdapter.FLAG_LISTEN_KEEP
                    )
                    Log.d(TAG, "setDiscoveryTechnology called to suppress active polling")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set preferred HCE service: ${e.message}", e)
        }
        
        // Re-start and re-setup HCE service in case it was killed while backgrounded
        val ndefAvailable = NdefHostCardEmulationService.isHceAvailable(this)
        if (ndefAvailable && hcePaymentRequest != null) {
            val serviceIntent = Intent(this, NdefHostCardEmulationService::class.java)
            startService(serviceIntent)
            setupNdefPayment()
        }
    }

    override fun onPause() {
        nfcSetupRunnable?.let { nfcSetupHandler.removeCallbacks(it) }
        try {
            val nfcAdapter = NfcAdapter.getDefaultAdapter(this)
            if (nfcAdapter != null) {
                val cardEmulation = CardEmulation.getInstance(nfcAdapter)
                cardEmulation.unsetPreferredService(this)
                Log.d(TAG, "unsetPreferredService for HCE")
                
                if (android.os.Build.VERSION.SDK_INT >= 35) {
                    nfcAdapter.resetDiscoveryTechnology(this)
                    Log.d(TAG, "resetDiscoveryTechnology called")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unset preferred HCE service: ${e.message}", e)
        }
        super.onPause()
    }

    private fun initializePaymentRequest() {
        statusText.visibility = View.VISIBLE
        statusText.text = getString(R.string.payment_request_status_preparing)

        // Create the payment service (BTCPay or Local)
        paymentService = PaymentServiceFactory.create(this)

        val isBtcPay = paymentService is BTCPayPaymentService

        if (isBtcPay) {
            val existingInvoiceId = if (isResumingPayment) resumeLightningQuoteId else null
            if (existingInvoiceId != null) {
                resumeBtcPayPaymentRequest(existingInvoiceId)
            } else {
                initializeBtcPayPaymentRequest()
            }
        } else {
            initializeLocalPaymentRequest()
        }
    }

    /**
     * BTCPay mode: create an invoice via BTCPay Server, display the bolt11 /
     * cashu QR codes from the response, and poll for payment status.
     */
    private fun initializeBtcPayPaymentRequest() {
        // Show spinner, hide QR and logo while createPayment() runs
        cashuQrImageView.visibility = View.INVISIBLE
        cashuLogoCard.visibility = View.GONE
        cashuLoadingSpinner.visibility = View.VISIBLE

        uiScope.launch {
            val result = paymentService.createPayment(paymentAmount, "Payment of $paymentAmount sats", checkoutBasketJson)
            result.onSuccess { payment ->
                btcPayPaymentId = payment.paymentId
                btcPayInvoiceCreatedAt = System.currentTimeMillis()
                // Persist invoice ID so resume can reuse it instead of creating a new one
                pendingPaymentId?.let {
                    PaymentsHistoryActivity.updatePendingWithLightningInfo(
                        context = this@PaymentRequestActivity,
                        paymentId = it,
                        lightningQuoteId = payment.paymentId,
                    )
                }

                val hasCashu = !payment.cashuPR.isNullOrBlank()
                val hasLightning = !payment.bolt11.isNullOrBlank()

                if (!hasCashu && !hasLightning) {
                    Log.e(TAG, "BTCPay returned no cashuPR and no bolt11 — cannot display payment")
                    cashuLoadingSpinner.visibility = View.GONE
                    handlePaymentError("BTCPay returned no payment methods")
                    return@onSuccess
                }

                // Show Cashu QR (cashuPR from BTCNutServer)
                if (hasCashu) {
                    val (cashuCbor, cashuBech32) = prepareBtcPayCashuPR(payment.cashuPR!!, paymentAmount)
                    btcPayCashuPR = cashuCbor
                    btcPayCashuPRBech32 = cashuBech32
                    try {
                        val qrBitmap = QrCodeGenerator.generate(cashuCbor, 512)
                        cashuQrImageView.setImageBitmap(qrBitmap)
                        cashuQrImageView.visibility = View.VISIBLE
                        cashuLogoCard.visibility = View.VISIBLE
                    } catch (e: Exception) {
                        Log.e(TAG, "Error generating BTCPay Cashu QR: ${e.message}", e)
                    }
                    cashuLoadingSpinner.visibility = View.GONE

                    hcePaymentRequest = CashuPaymentHelper.stripTransports(cashuCbor) ?: cashuCbor
                    if (NdefHostCardEmulationService.isHceAvailable(this@PaymentRequestActivity)) {
                        val serviceIntent = Intent(this@PaymentRequestActivity, NdefHostCardEmulationService::class.java)
                        startService(serviceIntent)
                        setupNdefPayment()
                    }
                } else {
                    // No cashuPR — disable Cashu tab and switch to Lightning
                    cashuLoadingSpinner.visibility = View.GONE
                    tabManager.disableTab(PaymentTabManager.Tab.CASHU)
                }

                // Show Lightning QR (may already be in the response, or fetch in background)
                if (hasLightning) {
                    showBtcPayLightningQr(payment.bolt11!!)
                } else {
                    // createPayment() broke early on cashuPR — fetch bolt11 in background
                    fetchBtcPayLightningInBackground(payment.paymentId)
                }

                statusText.text = getString(R.string.payment_request_status_waiting_for_payment)

                // Start polling BTCPay for payment status
                startBtcPayPolling(payment.paymentId)
            }.onFailure { error ->
                Log.e(TAG, "BTCPay createPayment failed: ${error.message}", error)
                cashuLoadingSpinner.visibility = View.GONE
                statusText.text = getString(R.string.payment_request_status_error_generic, error.message ?: "Unknown error")
            }
        }
    }

    private fun showBtcPayLightningQr(bolt11: String) {
        lightningInvoice = bolt11
        try {
            val qrBitmap = QrCodeGenerator.generate(bolt11, 512)
            lightningQrImageView.setImageBitmap(qrBitmap)
            lightningQrImageView.visibility = View.VISIBLE
            lightningLoadingSpinner.visibility = View.GONE
            lightningLogoCard.visibility = View.VISIBLE
        } catch (e: Exception) {
            Log.e(TAG, "Error generating BTCPay Lightning QR: ${e.message}", e)
            lightningLoadingSpinner.visibility = View.GONE
        }
        lightningStarted = true
        updateUnifiedQrCode()
        if (tabManager.getCurrentTab() == PaymentTabManager.PaymentTab.LIGHTNING) {
            setHceToLightning()
        }
    }

    /**
     * Resume a BTCPay payment using an existing invoice ID, avoiding creation of a new invoice.
     */
    private fun resumeBtcPayPaymentRequest(invoiceId: String) {
        cashuQrImageView.visibility = View.INVISIBLE
        cashuLogoCard.visibility = View.GONE
        cashuLoadingSpinner.visibility = View.VISIBLE

        val btcPay = paymentService as BTCPayPaymentService
        uiScope.launch {
            val result = btcPay.fetchExistingPaymentData(invoiceId)
            result.onSuccess { payment ->
                btcPayPaymentId = invoiceId
                btcPayInvoiceCreatedAt = System.currentTimeMillis()

                val hasCashu = !payment.cashuPR.isNullOrBlank()
                val hasLightning = !payment.bolt11.isNullOrBlank()

                if (!hasCashu && !hasLightning) {
                    cashuLoadingSpinner.visibility = View.GONE
                    handlePaymentError("BTCPay returned no payment methods")
                    return@onSuccess
                }

                if (hasCashu) {
                    val (cashuCbor, cashuBech32) = prepareBtcPayCashuPR(payment.cashuPR!!, paymentAmount)
                    btcPayCashuPR = cashuCbor
                    btcPayCashuPRBech32 = cashuBech32
                    try {
                        val qrBitmap = QrCodeGenerator.generate(cashuCbor, 512)
                        cashuQrImageView.setImageBitmap(qrBitmap)
                        cashuQrImageView.visibility = View.VISIBLE
                        cashuLogoCard.visibility = View.VISIBLE
                    } catch (e: Exception) {
                        Log.e(TAG, "Error generating BTCPay Cashu QR on resume: ${e.message}", e)
                    }
                    cashuLoadingSpinner.visibility = View.GONE
                    hcePaymentRequest = CashuPaymentHelper.stripTransports(cashuCbor) ?: cashuCbor
                    if (NdefHostCardEmulationService.isHceAvailable(this@PaymentRequestActivity)) {
                        startService(Intent(this@PaymentRequestActivity, NdefHostCardEmulationService::class.java))
                        setupNdefPayment()
                    }
                } else {
                    cashuLoadingSpinner.visibility = View.GONE
                    tabManager.disableTab(PaymentTabManager.Tab.CASHU)
                }

                if (hasLightning) {
                    showBtcPayLightningQr(payment.bolt11!!)
                } else {
                    fetchBtcPayLightningInBackground(invoiceId)
                }

                statusText.text = getString(R.string.payment_request_status_waiting_for_payment)
                startBtcPayPolling(invoiceId)
            }.onFailure { error ->
                Log.e(TAG, "BTCPay resume failed: ${error.message}", error)
                cashuLoadingSpinner.visibility = View.GONE
                // Only create a new invoice if the original is definitively gone (404/expired).
                // For network errors, show the error rather than risk duplicate invoices.
                val isInvoiceGone = error is WalletError.NetworkError &&
                    (error.message?.contains("404") == true || error.message?.contains("expired", ignoreCase = true) == true)
                if (isInvoiceGone) {
                    Log.d(TAG, "Invoice gone, creating new BTCPay invoice")
                    initializeBtcPayPaymentRequest()
                } else {
                    handlePaymentError(error.message ?: "Failed to load payment")
                }
            }
        }
    }

    private fun fetchBtcPayLightningInBackground(invoiceId: String) {
        val btcPay = paymentService as? BTCPayPaymentService ?: return
        uiScope.launch {
            for (attempt in 1..10) {
                delay(1500)
                if (hasTerminalOutcome) break
                val bolt11 = btcPay.fetchLightningInvoice(invoiceId)
                if (bolt11 != null) {
                    Log.d(TAG, "Got bolt11 in background after $attempt attempt(s)")
                    showBtcPayLightningQr(bolt11)
                    return@launch
                }
                Log.d(TAG, "Background bolt11 fetch attempt $attempt — not ready yet")
            }
            // All attempts exhausted — hide spinner
            Log.w(TAG, "Lightning invoice not available after all attempts")
            lightningLoadingSpinner.visibility = View.GONE
        }
    }

    /**
     * Local (CDK) mode: the original flow – NDEF, Nostr, and Lightning tab.
     */
    private fun initializeLocalPaymentRequest() {
        // Get allowed mints supporting the active unit
        val mintManager = MintManager.getInstance(this)
        val activeUnit = mintManager.getPreferredUnit()
        val allowedMints = mintManager.getAllowedMints().filter { mintManager.mintSupportsUnit(it, activeUnit) }
        Log.d(TAG, "Using ${allowedMints.size} allowed mints for payment request")

        // Initialize Lightning handler with preferred mint (will be started when tab is selected)
        val preferredLightningMint = mintManager.getPreferredLightningMint()
        lightningHandler = LightningMintHandler(this, preferredLightningMint, allowedMints, uiScope)

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

            val generatedHce = CashuPaymentHelper.createPaymentRequest(
                paymentAmount,
                getString(R.string.payment_request_default_description, paymentAmount),
                mintsForPaymentRequest
            )
            hcePaymentRequest = generatedHce?.original
            hcePaymentRequestBech32 = generatedHce?.bech32

            if (hcePaymentRequest == null) {
                Log.e(TAG, "Failed to create payment request for HCE")
                Toast.makeText(this, R.string.payment_request_error_ndef_prepare, Toast.LENGTH_SHORT).show()
            } else {
                Log.d(TAG, "Created HCE payment request: $hcePaymentRequest")
                // HCE service will be started and configured in onResume()
            }
        }

        // Initialize Nostr handler and start payment flow
        nostrHandler = NostrPaymentHandler(this, allowedMints)
        startNostrPaymentFlow()

        // Check mint limits for the preferred mint to see if lightning bolt11 is supported
        uiScope.launch {
            val mintUrlToUse = preferredLightningMint ?: allowedMints.firstOrNull()
            
            if (mintUrlToUse != null) {
                val limits = mintManager.getMintLimits(mintUrlToUse, this@PaymentRequestActivity)
                val preferredUnit = MintManager.getInstance(this@PaymentRequestActivity).getPreferredUnit()
                val checkResult = MintLimitChecker.checkMintLimits(paymentAmount, limits, preferredUnit)
                isBolt11Supported = checkResult.isBolt11Supported
            }
            
            if (!isBolt11Supported) {
                Log.d(TAG, "Mint does not support bolt11. Bypassing Lightning tab and showing BIP321 Cashu request.")
                // Bypass lightning entirely
                runOnUiThread {
                    // Hide Lightning tab ONLY
                    lightningTab.visibility = View.GONE
                    
                    // Force selecting UNIFIED tab if LIGHTNING was the default
                    if (tabManager.getCurrentTab() == PaymentTabManager.PaymentTab.LIGHTNING) {
                        tabManager.selectTab(PaymentTabManager.PaymentTab.UNIFIED)
                    }
                    
                    // We need to call updateUnifiedQrCode here because the creq might already be ready
                    updateUnifiedQrCode()
                }
            } else {
                // Unless developer setting to delay it is enabled, start it immediately
                if (!DeveloperPrefs.isLightningInvoiceDelayed(this@PaymentRequestActivity)) {
                    startLightningMintFlow()
                }
            }
        }
    }

    /**
     * Poll BTCPay invoice status every 2 seconds until terminal state.
     */
    private fun startBtcPayPolling(paymentId: String) {
        btcPayPollingJob = uiScope.launch {
            var consecutiveErrors = 0
            var pollInterval = 2000L

            while (!hasTerminalOutcome) {
                // Fix 7: local expiry guard (15 min) in case server never returns EXPIRED
                if (btcPayInvoiceCreatedAt > 0 &&
                    System.currentTimeMillis() - btcPayInvoiceCreatedAt > BTCPAY_INVOICE_TIMEOUT_MS) {
                    Log.w(TAG, "BTCPay invoice timed out locally after ${BTCPAY_INVOICE_TIMEOUT_MS / 60000} min")
                    btcPayPollingJob?.cancel()
                    pendingPaymentId?.let { PaymentsHistoryActivity.markPaymentExpired(this@PaymentRequestActivity, it) }
                    handlePaymentError("Invoice expired")
                    return@launch
                }

                delay(pollInterval)
                if (hasTerminalOutcome) break

                val statusResult = paymentService.checkPaymentStatus(paymentId)
                statusResult.onSuccess { state ->
                    consecutiveErrors = 0
                    pollInterval = 2000L
                    when (state) {
                        PaymentState.PAID -> {
                            btcPayPollingJob?.cancel()
                            val type = when (currentHceMode) {
                                HceMode.CASHU, HceMode.UNIFIED -> PaymentHistoryEntry.TYPE_CASHU
                                HceMode.LIGHTNING -> PaymentHistoryEntry.TYPE_LIGHTNING
                            }
                            handleLightningPaymentSuccess(type, btcPayInvoiceId = btcPayPaymentId)
                        }
                        PaymentState.EXPIRED -> {
                            btcPayPollingJob?.cancel()
                            pendingPaymentId?.let { PaymentsHistoryActivity.markPaymentExpired(this@PaymentRequestActivity, it) }
                            handlePaymentError("Invoice expired")
                        }
                        PaymentState.FAILED -> {
                            btcPayPollingJob?.cancel()
                            pendingPaymentId?.let { PaymentsHistoryActivity.markPaymentFailed(this@PaymentRequestActivity, it) }
                            handlePaymentError("Invoice invalid")
                        }
                        PaymentState.PENDING -> { /* continue */ }
                    }
                }.onFailure { error ->
                    // Fix 6: exponential backoff, stop after too many consecutive errors
                    consecutiveErrors++
                    pollInterval = minOf(pollInterval * 2, 30_000L)
                    Log.w(TAG, "BTCPay poll error ($consecutiveErrors): ${error.message}")
                    if (consecutiveErrors >= BTCPAY_MAX_POLL_ERRORS) {
                        Log.e(TAG, "BTCPay polling stopped after $consecutiveErrors consecutive errors")
                        btcPayPollingJob?.cancel()
                        handlePaymentError("Server unreachable")
                    }
                }
            }
        }
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
        val payload = invoice

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

    private fun setHceToUnified() {
        // In BTCPay mode hcePaymentRequestBech32 is not set; fall back to stripped cashuPR
        val creq = hcePaymentRequestBech32 ?: hcePaymentRequest
        val lnbc = lightningInvoice

        if (creq == null && lnbc == null) {
            Log.w(TAG, "setHceToUnified() called but both creq and lnbc are null")
            return
        }

        val payload = org.cashudevkit.createBip321Uri(creq, lnbc, null)

        try {
            val hceService = NdefHostCardEmulationService.getInstance()
            if (hceService != null) {
                Log.d(TAG, "setHceToUnified(): Switching HCE payload to Unified. payload=$payload")
                hceService.setPaymentRequest(payload ?: "", paymentAmount)
                currentHceMode = HceMode.UNIFIED
            } else {
                Log.w(TAG, "setHceToUnified(): HCE service not available")
            }
        } catch (e: Exception) {
            Log.e(TAG, "setHceToUnified(): Error while setting HCE Unified payload: ${e.message}", e)
        }
    }

    private fun generateThemedQrCode(text: String): android.graphics.Bitmap {
        val currentNightMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        val isDarkTheme = currentNightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val qrForeground = if (isDarkTheme) android.graphics.Color.WHITE else android.graphics.Color.BLACK
        val qrBackground = android.graphics.Color.TRANSPARENT
        return QrCodeGenerator.generate(text, 512, qrForeground, qrBackground)
    }

    private fun prepareBtcPayCashuPR(rawCashuPR: String, amount: Long): Pair<String, String?> =
        BtcPayQrCodeBuilder.prepareCashuQrContent(rawCashuPR, amount)

    private fun updateUnifiedQrCode() {
        val creq = nostrHandler?.paymentRequestBech32 ?: hcePaymentRequestBech32 ?: btcPayCashuPRBech32 ?: btcPayCashuPR ?: hcePaymentRequest
        val lnbc = lightningInvoice


        // We only show the unified QR when BOTH Cashu and Lightning requests are ready
        // (unless lightning is explicitly disabled or errored out, but for simplicity we assume we need both if Lightning is supported)
        
        // Since we attempt to fetch lightning invoice by default if allowed mints are set, 
        // we'll wait for both unless lnbc fails (which we handle below).
        // Let's implement the logic: If we have creq, we still want to wait for lnbc if lightning was started.
        
        // Actually, if we don't have creq yet, definitely wait.
        if (creq == null) return
        
        // If Lightning is enabled (lightningStarted is true) and we don't have lnbc yet, wait.
        // If lnbc is null because of an error, it stays null, but we don't want to spin forever. 
        // Wait, if there's an error, onError hides the lightning spinner. But does it hide the unified spinner? 
        // We should just hide the unified spinner and show creq if lnbc fails, or we should never show it?
        // For simplicity: if lightningStarted == true and lightningInvoice == null, we wait. BUT wait, how do we know if it errored? 
        // Let's just track if lightning is "in progress". For now, we will require both. If one fails, the user is notified.
        
        if (lightningStarted && lnbc == null) {
            // Still waiting for lightning invoice
            return
        }
        
        val unifiedUri = org.cashudevkit.createBip321Uri(creq, lnbc, null)

        try {
            val qrBitmap = generateThemedQrCode(unifiedUri)
            unifiedQrImageView.setImageBitmap(qrBitmap)
            unifiedQrImageView.visibility = View.VISIBLE
            unifiedLoadingSpinner.visibility = View.GONE
            
            if (tabManager.getCurrentTab() == PaymentTabManager.PaymentTab.UNIFIED) {
                setHceToUnified()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating Unified QR bitmap: ${e.message}", e)
        }
    }

    private fun startNostrPaymentFlow() {
        val handler = nostrHandler ?: return

        val callback = object : NostrPaymentHandler.Callback {
            override fun onPaymentRequestReady(paymentRequest: String) {
                try {
                    val qrBitmap = generateThemedQrCode(paymentRequest)
                    cashuQrImageView.setImageBitmap(qrBitmap)
                    cashuQrImageView.visibility = View.VISIBLE
                    cashuLoadingSpinner.visibility = View.GONE
                    statusText.text = getString(R.string.payment_request_status_waiting_for_payment)
                    updateUnifiedQrCode()
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

            override fun onPaymentFailure(message: String) {
                Log.e(TAG, "Nostr payment failure: $message")
                // Atomically update Nostr identity ONLY on payment failure so retries won't hit the same event
                nostrHandler?.rotateKeys(pendingPaymentId)
                runOnUiThread {
                    handlePaymentError("Nostr payment failed: $message")
                }
            }

            override fun onError(message: String) {
                Log.e(TAG, "Nostr payment error: $message")
                runOnUiThread {
                    handlePaymentError("Nostr payment error: $message")
                }
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
                    val qrBitmap = generateThemedQrCode(bolt11)
                    lightningQrImageView.setImageBitmap(qrBitmap)
                    lightningQrImageView.visibility = View.VISIBLE
                    // Hide loading spinner and show the bolt icon
                    lightningLoadingSpinner.visibility = View.GONE
                    lightningLogoCard.visibility = View.VISIBLE
                    updateUnifiedQrCode()
                } catch (e: Exception) {
                    Log.e(TAG, "Error generating Lightning QR bitmap: ${e.message}", e)
                    // Still hide spinner on error
                    lightningLoadingSpinner.visibility = View.GONE
                }

                // If Lightning tab is currently visible, switch HCE payload to Lightning
                if (tabManager.getCurrentTab() == PaymentTabManager.PaymentTab.LIGHTNING) {
                    Log.d(TAG, "onInvoiceReady(): Lightning tab is selected, calling setHceToLightning()")
                    setHceToLightning()
                }
            }

            override fun onPaymentSuccess() {
                handleLightningPaymentSuccess()
            }

            override fun onError(message: String) {
                // Hide loading spinner on error
                lightningLoadingSpinner.visibility = View.GONE
                lightningStarted = false // Mark as finished/failed so unified QR can proceed with just Cashu
                updateUnifiedQrCode()
                
                // Do not immediately fail the whole payment; NFC or Nostr may still succeed.
                // Surface a toast to inform the user that the Unified QR will only contain Cashu, 
                // or that the Lightning tab is unavailable.
                val errorMsg = getString(R.string.payment_request_lightning_error_failed, message)
                Toast.makeText(this@PaymentRequestActivity, errorMsg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setupNdefPayment() {
        val request = hcePaymentRequest ?: return

        // Match original behavior: slight delay before configuring service
        nfcSetupRunnable = Runnable {
            val hceService = NdefHostCardEmulationService.getInstance()
            if (hceService != null) {
                Log.d(TAG, "Setting up NDEF payment with HCE service")

                // Set the payment request to the HCE service based on current tab selection
                when (tabManager.getCurrentTab()) {
                    PaymentTabManager.PaymentTab.LIGHTNING -> {
                        if (lightningInvoice != null) {
                            setHceToLightning()
                        } else {
                            setHceToCashu()
                        }
                    }
                    PaymentTabManager.PaymentTab.UNIFIED -> {
                        setHceToUnified()
                    }
                    PaymentTabManager.PaymentTab.CASHU -> {
                        setHceToCashu()
                    }
                }

                // Set up callback for when a token is received or an error occurs
                hcePaymentCallback = object : NdefHostCardEmulationService.CashuPaymentCallback {
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

                            // Transition UI to PROCESSING stage
                            runOnUiThread {
                                showNfcAnimationProcessing()
                            }

                            // Cancel the NFC safety timeout immediately as we have received data.
                            // The subsequent processing (swap/redemption) may take longer than
                            // the NFC timeout allows, but that is a network operation, not an NFC one.
                            cancelNfcSafetyTimeout()
                            Log.d(TAG, "NFC token received, cancelled safety timeout")

                            try {
                                // If using BTCPay, redeem the token via the BTCNutServer API.
                                if (paymentService is BTCPayPaymentService) {
                                    val invoiceId = btcPayPaymentId
                                    if (invoiceId != null) {
                                        Log.d(TAG, "Redeeming NFC token via BTCPay /cashu/pay-invoice")
                                        val result = paymentService.redeemToken(token, invoiceId)
                                        result.onSuccess {
                                            // BTCPay now returns 200 when the token is accepted for
                                            // processing, not when the invoice is settled. Settlement
                                            // is confirmed by the polling loop (startBtcPayPolling)
                                            // which calls handleLightningPaymentSuccess on PAID.
                                            Log.d(TAG, "BTCPay token submitted — waiting for invoice settlement via polling")
                                        }.onFailure { e ->
                                            // Redemption failed — check BTCPay invoice status to see
                                            // if it settled anyway (e.g. race condition).
                                            Log.w(TAG, "BTCPay NFC redemption failed: ${e.message} — checking invoice status")
                                            val statusResult = paymentService.checkPaymentStatus(invoiceId)
                                            statusResult.onSuccess { state ->
                                                when (state) {
                                                    PaymentState.PAID -> withContext(Dispatchers.Main) {
                                                        handleLightningPaymentSuccess(PaymentHistoryEntry.TYPE_CASHU)
                                                    }
                                                    PaymentState.PENDING -> withContext(Dispatchers.Main) {
                                                        handlePaymentError(e.message ?: "NFC payment failed")
                                                    }
                                                    else -> throw Exception("BTCPay redemption failed: ${e.message}")
                                                }
                                            }.onFailure {
                                                throw Exception("BTCPay redemption failed: ${e.message}")
                                            }
                                        }
                                        return@launch
                                    } else {
                                        Log.w(TAG, "BTCPay invoice ID not available, falling back to local flow (likely to fail)")
                                    }
                                }

                                val paymentId = pendingPaymentId
                                val paymentContext = com.electricdreams.numo.payment.SwapToLightningMintManager.PaymentContext(
                                    paymentId = paymentId,
                                    amountSats = paymentAmount,
                                )

                                val mintManager = MintManager.getInstance(this@PaymentRequestActivity)
                                val activeUnit = mintManager.getPreferredUnit()
                                val allowedMints = mintManager.getAllowedMints().filter { mintManager.mintSupportsUnit(it, activeUnit) }

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
                            if (currentHceMode == HceMode.LIGHTNING) {
                                Log.d(TAG, "NFC reading started ignored - currently in Lightning mode")
                                return@runOnUiThread
                            }
                            Log.d(TAG, "NFC reading started - showing animation overlay")
                            showNfcAnimationOverlay()
                        }
                    }

                    override fun onNfcReadingStopped(failedInMiddleOfTransaction: Boolean) {
                        runOnUiThread {
                            Log.w(TAG, "NFC reading stopped callback received (failedInMiddleOfTransaction: $failedInMiddleOfTransaction)")
                            if (hasTerminalOutcome) {
                                Log.d(TAG, "NFC reading stopped ignored - terminal outcome already set")
                                return@runOnUiThread
                            }

                            if (failedInMiddleOfTransaction) {
                                Log.e(TAG, "NFC connection lost while writing data - failing payment")
                                handlePaymentError(PaymentErrorMessages.RAW_NFC_CONNECTION_LOST)
                            } else {
                                Log.d(TAG, "NFC connection stopped without writing data. Returning to payment request screen.")
                                hideNfcAnimationOverlay()
                            }
                        }
                    }
                }
                hceService.setPaymentCallback(hcePaymentCallback)

                Log.d(TAG, "NDEF payment service ready")
            }
        }
        nfcSetupRunnable?.let { nfcSetupHandler.postDelayed(it, 1000) }
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
            org.cashudevkit.Token.decode(token).mintUrl().url
        } catch (e: Exception) {
            null
        }

        // Update pending payment to completed (Cashu payment path)
        pendingPaymentId?.let { paymentId ->
            val creq = nostrHandler?.paymentRequestBech32 
                ?: hcePaymentRequestBech32 
                ?: btcPayCashuPRBech32 
                ?: btcPayCashuPR 
                ?: hcePaymentRequest
            PaymentsHistoryActivity.completePendingPayment(
                context = this,
                paymentId = paymentId,
                token = token,
                paymentType = PaymentHistoryEntry.TYPE_CASHU,
                mintUrl = mintUrl,
                lightningInvoice = creq,
            )
        }

        dispatchPaymentReceivedWebhook()

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
    private fun handleLightningPaymentSuccess(
        paymentType: String = PaymentHistoryEntry.TYPE_LIGHTNING,
        btcPayInvoiceId: String? = null,
    ) {
        // Guard against late callbacks so we don't surface a failure screen
        // after a successful Lightning payment has already been processed.
        if (!beginTerminalOutcome("lightning_success")) return

        Log.d(TAG, "Lightning payment successful (no Cashu token)")
        cancelNfcSafetyTimeout()

        WalletLogger.log("IN", paymentAmount, lightningMintUrl ?: "Unknown", "Lightning payment successful (NFC)")

        statusText.visibility = View.VISIBLE
        statusText.text = getString(R.string.payment_request_status_success)

        // Update pending payment to completed with Lightning info
        pendingPaymentId?.let { paymentId ->
            PaymentsHistoryActivity.completePendingPayment(
                context = this,
                paymentId = paymentId,
                token = "",
                paymentType = paymentType,
                mintUrl = lightningMintUrl,
                lightningInvoice = lightningInvoice,
                lightningQuoteId = lightningQuoteId,
                lightningMintUrl = lightningMintUrl,
                btcPayInvoiceId = btcPayInvoiceId,
            )
        }

        dispatchPaymentReceivedWebhook()

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
    /**
     * Clears the payment request and unregisters the callback from the active HCE service.
     */
    private fun clearHceService() {
        try {
            val hceService = NdefHostCardEmulationService.getInstance()
            if (hceService != null && hcePaymentCallback != null) {
                if (hceService.paymentCallback === hcePaymentCallback) {
                    Log.d(TAG, "clearHceService: Clearing HCE payment request and callback")
                    hceService.clearPaymentRequest()
                    hceService.setPaymentCallback(null)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing HCE service: ${e.message}", e)
        }
    }

    private fun beginTerminalOutcome(reason: String): Boolean {
        if (hasTerminalOutcome) {
            Log.w(TAG, "Ignoring terminal outcome after completion. reason=$reason")
            return false
        }
        hasTerminalOutcome = true

        // Immediately stop/clear NFC/HCE service to prevent paying wallets from attempting again
        clearHceService()

        return true
    }

    private fun handlePaymentError(errorMessage: String) {
        // If we've already processed a terminal outcome (e.g. a successful
        // payment), ignore late errors so we don't show the failure screen
        // on top of a genuine success.
        if (!beginTerminalOutcome("error: $errorMessage")) return

        Log.e(TAG, "Payment error: $errorMessage")
        cancelNfcSafetyTimeout()

        val reason = PaymentErrorMessages.reasonFor(errorMessage)
        statusText.visibility = View.VISIBLE
        statusText.text = getString(R.string.payment_request_status_failed, getString(reason.messageRes))
        setResult(Activity.RESULT_CANCELED)

        // Always render the native failure overlay so failure paths stay consistent,
        // mirroring the behavior of showPaymentSuccess.
        if (nfcAnimationContainer.visibility != View.VISIBLE) {
            openPaymentOverlay(loading = false)
        } else {
            applyFullscreenForAnimationOverlay()
        }

        showNfcAnimationError(reason)
    }

    private fun cancelPayment() {
        if (hasTerminalOutcome && currentOverlayActionMode == OverlayActionMode.SUCCESS) {
            Log.d(TAG, "Payment already successful, not cancelling")
            animateSuccessScreenOut()
            return
        }

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

        nfcSetupRunnable?.let { nfcSetupHandler.removeCallbacks(it) }

        cancelNfcSafetyTimeout()

        // Stop BTCPay polling
        btcPayPollingJob?.cancel()
        btcPayPollingJob = null

        // Stop Nostr handler
        nostrHandler?.stop()
        nostrHandler = null

        // Stop Lightning handler
        lightningHandler?.cancel()
        lightningHandler = null

        // Clean up HCE service
        clearHceService()

        cancelPendingResultReveal()
        runPendingPostPaymentOperations()
        nfcAnimationView.reset()
        nfcAnimationContainer.visibility = View.GONE
        resetResultTextViews()
        resetResultActionButtons()
        restoreSystemBarsAfterAnimation()

        finish()
    }

    override fun onDestroy() {
        nfcSetupRunnable?.let { nfcSetupHandler.removeCallbacks(it) }
        cancelNfcSafetyTimeout()
        cancelPendingResultReveal()
        runPendingPostPaymentOperations()
        btcPayPollingJob?.cancel()
        btcPayPollingJob = null
        nostrHandler?.stop()
        nostrHandler = null
        lightningHandler?.cancel()
        lightningHandler = null

        // Clean up HCE service
        clearHceService()

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

    private fun dispatchPaymentReceivedWebhook() {
        val paymentId = pendingPaymentId ?: run {
            Log.w(TAG, "Skipping webhook dispatch: pendingPaymentId is null")
            return
        }

        val completedEntry = PaymentsHistoryActivity.getPaymentEntryById(this, paymentId)
        if (completedEntry == null) {
            Log.w(TAG, "Skipping webhook dispatch: no history entry found for paymentId=$paymentId")
            return
        }

        PaymentWebhookDispatcher.getInstance(this).dispatchPaymentReceived(completedEntry)
    }

    /**
     * Unified success handler for all payment types.
     * Always renders the native success overlay so NFC and non-NFC success paths stay consistent.
     */
    private fun showPaymentSuccess(token: String, amount: Long) {
        if (nfcAnimationContainer.visibility != View.VISIBLE) {
            openPaymentOverlay(loading = false)
        } else {
            applyFullscreenForAnimationOverlay()
        }
        pendingNfcSuccessToken = token
        pendingNfcSuccessAmount = amount
        showNfcAnimationSuccess(formattedAmountString)
    }

    // ============================================================
    // Payment overlay (NFC loading + result for every payment path)
    // ============================================================

    private fun setupNfcAnimationOverlay() {
        animationCloseButton.setOnClickListener {
            animateSuccessScreenOut()
        }
        animationViewDetailsButton.setOnClickListener {
            onOverlaySecondaryActionPressed()
        }

        nfcAnimationView.setAnchor(nfcIndicatorSlot)

        // When the error reason appears, glide the column into its new centre instead of
        // jumping; the reason itself fades in via animateResultTextIn.
        findViewById<ViewGroup>(R.id.nfc_overlay_content).layoutTransition?.apply {
            disableTransitionType(LayoutTransition.APPEARING)
            disableTransitionType(LayoutTransition.DISAPPEARING)
            setDuration(LayoutTransition.CHANGE_APPEARING, RESULT_TEXT_IN_MS)
            setDuration(LayoutTransition.CHANGE_DISAPPEARING, RESULT_TEXT_IN_MS)
            setInterpolator(LayoutTransition.CHANGE_APPEARING, DecelerateInterpolator())
            setStartDelay(LayoutTransition.CHANGE_APPEARING, 0)
        }
        nfcAnimationView.setOnResultDisplayedListener { success ->
            runOnUiThread {
                onNfcAnimationResultDisplayed(success)
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(nfcAnimationContainer) { _, insets ->
            adjustOverlayForInsets(insets)
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
        cancelPendingResultReveal()
        runPendingPostPaymentOperations()

        // Stop Nostr handler
        nostrHandler?.stop()
        nostrHandler = null

        // Stop Lightning handler
        lightningHandler?.cancel()
        lightningHandler = null

        // Clean up HCE service
        clearHceService()

        finish()

        // Fade out the success screen, fade in the home screen
        // Must be called immediately after finish() to take effect
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
    }

    /**
     * Shows the overlay on the app background with the amount being charged. With [loading],
     * the NFC loader and hint are shown; otherwise the overlay opens straight into a result.
     */
    private fun openPaymentOverlay(loading: Boolean) {
        cancelPendingResultReveal()
        nfcOverlayShownAtMs = SystemClock.elapsedRealtime()
        Log.d(TAG, "overlay_shown_ms=$nfcOverlayShownAtMs")

        overlayOnSuccessColor = false
        nfcAnimationView.reset()
        resetResultTextViews()
        resetResultActionButtons()

        animationResultAmountText.text = formattedAmountString
        animationResultAmountText.visibility = View.VISIBLE

        if (loading) {
            nfcHintText.text = getString(R.string.nfc_payment_hint_keep_close)
            nfcHintText.visibility = View.VISIBLE
            nfcLoader.visibility = View.VISIBLE
            nfcAnimationStartedAtMs = SystemClock.elapsedRealtime()
        } else {
            nfcAnimationStartedAtMs = 0
        }

        nfcAnimationContainer.animate().cancel()
        nfcAnimationContainer.visibility = View.VISIBLE
        if (ReducedMotion.isEnabled(this)) {
            nfcAnimationContainer.alpha = 1f
        } else {
            nfcAnimationContainer.alpha = 0f
            nfcAnimationContainer.animate()
                .alpha(1f)
                .setDuration(OVERLAY_FADE_IN_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
        applyFullscreenForAnimationOverlay()
        ViewCompat.requestApplyInsets(nfcAnimationContainer)
    }

    private fun showNfcAnimationOverlay() {
        pendingNfcSuccessToken = null
        pendingNfcSuccessAmount = 0
        openPaymentOverlay(loading = true)
        Log.d(TAG, "animation_started_ms=${nfcAnimationStartedAtMs - nfcOverlayShownAtMs}")
        startNfcSafetyTimeout()
    }

    /**
     * The token has arrived. The loader keeps going unchanged; only the hint changes so the
     * customer knows they can lift their phone.
     */
    private fun showNfcAnimationProcessing() {
        if (nfcAnimationContainer.visibility != View.VISIBLE) return

        try {
            val vibrator = getVibrator()
            vibrator?.vibrateCompat(longArrayOf(0, 50), -1)
        } catch (e: Exception) {
            Log.w(TAG, "Vibration failed", e)
        }

        crossfadeHint(getString(R.string.nfc_payment_hint_processing))
    }

    private fun crossfadeHint(text: String) {
        nfcHintText.animate().cancel()
        if (ReducedMotion.isEnabled(this)) {
            nfcHintText.text = text
            nfcHintText.alpha = 1f
            return
        }
        nfcHintText.animate()
            .alpha(0f)
            .setDuration(HINT_FADE_OUT_MS)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                nfcHintText.text = text
                nfcHintText.animate()
                    .alpha(1f)
                    .setDuration(HINT_FADE_IN_MS)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            .start()
    }

    private fun hideNfcAnimationOverlay() {
        cancelPendingResultReveal()
        nfcAnimationContainer.animate().cancel()
        nfcAnimationContainer.visibility = View.GONE
        nfcAnimationView.reset()
        resetResultTextViews()
        resetResultActionButtons()
        restoreSystemBarsAfterAnimation()
        cancelNfcSafetyTimeout()
        isProcessingNfcPayment = false
    }

    private fun startNfcSafetyTimeout() {
        cancelNfcSafetyTimeout()

        val runnable = Runnable {
            if (hasTerminalOutcome || nfcAnimationContainer.visibility != View.VISIBLE) {
                return@Runnable
            }
            Log.e(TAG, "NFC safety timeout triggered - payment did not reach terminal state")
            handlePaymentError(PaymentErrorMessages.RAW_NFC_TIMEOUT)
        }
        nfcAnimationTimeoutRunnable = runnable
        nfcTimeoutHandler.postDelayed(runnable, NFC_READ_TIMEOUT_MS)
    }

    private fun cancelNfcSafetyTimeout() {
        nfcAnimationTimeoutRunnable?.let {
            Log.d(TAG, "Cancelling Activity NFC safety timeout")
            nfcTimeoutHandler.removeCallbacks(it)
        }
        nfcAnimationTimeoutRunnable = null
    }

    /**
     * Runs [reveal] once the loader has been visible for [NFC_MIN_LOADING_MS], so a fast tap
     * doesn't flicker through states. Runs immediately when the overlay opened straight into
     * a result.
     */
    private fun revealResultAfterMinimumLoading(reveal: () -> Unit) {
        cancelPendingResultReveal()
        val remainingMs = if (nfcAnimationStartedAtMs > 0) {
            NFC_MIN_LOADING_MS - (SystemClock.elapsedRealtime() - nfcAnimationStartedAtMs)
        } else {
            0L
        }
        if (remainingMs <= 0L) {
            reveal()
            return
        }
        val runnable = Runnable {
            pendingResultReveal = null
            if (nfcAnimationContainer.visibility == View.VISIBLE) reveal()
        }
        pendingResultReveal = runnable
        nfcAnimationContainer.postDelayed(runnable, remainingMs)
    }

    private fun cancelPendingResultReveal() {
        pendingResultReveal?.let { nfcAnimationContainer.removeCallbacks(it) }
        pendingResultReveal = null
    }

    private fun showNfcAnimationSuccess(amountText: String) {
        if (nfcAnimationContainer.visibility != View.VISIBLE) return

        currentOverlayActionMode = OverlayActionMode.SUCCESS
        animationResultAmountText.text = amountText
        animationResultLabelText.text = getString(R.string.transaction_detail_type_payment_received)

        revealResultAfterMinimumLoading {
            overlayOnSuccessColor = true
            fadeOutLoading()
            animateAmountColor(Color.WHITE)
            applyFullscreenForAnimationOverlay()
            nfcAnimationView.showSuccess()
            playNfcSuccessFeedback()
        }
    }

    private fun playNfcSuccessFeedback() {
        // Play success sound
        try {
            val mediaPlayer = android.media.MediaPlayer.create(this, R.raw.success_sound)
            mediaPlayer?.setOnCompletionListener { it.release() }
            mediaPlayer?.start()
        } catch (e: Exception) {
            Log.w(TAG, "Success sound failed", e)
        }

        // Vibrate
        val vibrator = getVibrator()
        vibrator?.vibrateCompat(longArrayOf(0, 50, 100, 50), -1)
    }

    private fun showNfcAnimationError(reason: PaymentErrorReason) {
        if (nfcAnimationContainer.visibility != View.VISIBLE) return

        currentOverlayActionMode = OverlayActionMode.ERROR
        animationResultLabelText.text = getString(R.string.payment_failure_title)
        nfcResultReasonText.text = getString(reason.messageRes)
        showFailureReassurance = reason.noFundsMoved

        revealResultAfterMinimumLoading {
            fadeOutLoading()
            animateAmountColor(ContextCompat.getColor(this, R.color.color_text_secondary))
            nfcAnimationView.showError()
        }
    }

    /** Hides the loader and hint as the result takes over their slot. */
    private fun fadeOutLoading() {
        nfcLoader.animate().cancel()
        nfcHintText.animate().cancel()
        if (ReducedMotion.isEnabled(this)) {
            nfcLoader.visibility = View.INVISIBLE
            nfcHintText.visibility = View.INVISIBLE
            return
        }
        nfcLoader.animate()
            .alpha(0f)
            .scaleX(LOADER_EXIT_SCALE)
            .scaleY(LOADER_EXIT_SCALE)
            .setDuration(LOADER_EXIT_MS)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { nfcLoader.visibility = View.INVISIBLE }
            .start()
        nfcHintText.animate()
            .alpha(0f)
            .setDuration(LOADER_EXIT_MS)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { nfcHintText.visibility = View.INVISIBLE }
            .start()
    }

    /** Recolours the amount in step with the result (white on the green reveal). */
    private fun animateAmountColor(targetColor: Int) {
        amountColorAnimator?.cancel()
        val startColor = animationResultAmountText.currentTextColor
        if (ReducedMotion.isEnabled(this)) {
            animationResultAmountText.setTextColor(targetColor)
            return
        }
        amountColorAnimator = ValueAnimator.ofArgb(startColor, targetColor).apply {
            duration = AMOUNT_RECOLOR_MS
            startDelay = AMOUNT_RECOLOR_DELAY_MS
            addUpdateListener { animationResultAmountText.setTextColor(it.animatedValue as Int) }
            start()
        }
    }

    private fun onNfcAnimationResultDisplayed(success: Boolean) {
        val elapsedMs = if (nfcOverlayShownAtMs > 0) {
            SystemClock.elapsedRealtime() - nfcOverlayShownAtMs
        } else {
            0L
        }
        Log.d(TAG, "success_rendered_ms=$elapsedMs")

        val mode = if (success) OverlayActionMode.SUCCESS else OverlayActionMode.ERROR
        animateResultTextIn(success)
        showResultActionsAnimated(mode)

        if (success) runPendingPostPaymentOperations()
    }

    /**
     * Runs basket archiving and auto-withdrawal for a success that hasn't had them yet.
     * Normally this happens once the result animation finishes; cleanup paths call it too,
     * so leaving the screen mid-animation (or during the minimum loading time) never skips them.
     */
    private fun runPendingPostPaymentOperations() {
        val token = pendingNfcSuccessToken ?: return
        pendingNfcSuccessToken = null
        pendingNfcSuccessAmount = 0
        triggerPostPaymentOperations(token)
    }

    private fun showResultActionsAnimated(mode: OverlayActionMode) {
        applyFullscreenForAnimationOverlay()
        ViewCompat.requestApplyInsets(nfcAnimationContainer)

        currentOverlayActionMode = mode
        val onGreen = mode == OverlayActionMode.SUCCESS
        animationViewDetailsButton.text = getString(
            if (onGreen) {
                R.string.payment_received_button_view_details
            } else {
                R.string.payment_failure_button_try_again
            }
        )
        animationCloseButton.text = getString(
            if (onGreen) {
                R.string.payment_request_animation_close
            } else {
                R.string.payment_failure_button_close
            }
        )
        // On green the buttons are white text and a black pill; on the app background
        // they follow the theme's primary colours.
        animationViewDetailsButton.setTextColor(
            if (onGreen) Color.WHITE else ContextCompat.getColor(this, R.color.color_text_primary)
        )
        animationCloseButton.setBackgroundResource(
            if (onGreen) R.drawable.bg_button_black else R.drawable.bg_button_primary_black
        )
        animationCloseButton.setTextColor(
            ContextCompat.getColor(
                this,
                if (onGreen) R.color.color_text_on_dark else R.color.color_bg_white,
            )
        )

        animationViewDetailsButton.isEnabled = true
        animationCloseButton.isEnabled = true
        animationActionsContainer.visibility = View.VISIBLE

        if (ReducedMotion.isEnabled(this)) {
            animationActionsContainer.alpha = 1f
            animationActionsContainer.translationY = 0f
            return
        }
        animationActionsContainer.alpha = 0f
        animationActionsContainer.translationY = dpToPx(16f).toFloat()
        animationActionsContainer.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(RESULT_ACTIONS_DELAY_MS)
            .setDuration(RESULT_TEXT_IN_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /** Brings in the result title (and, for errors, the reason) below the badge. */
    private fun animateResultTextIn(success: Boolean) {
        val onColor = if (success) Color.WHITE else ContextCompat.getColor(this, R.color.color_text_primary)
        animationResultLabelText.setTextColor(onColor)

        val views = mutableListOf<View>(animationResultLabelText)
        if (!success) {
            views += nfcResultReasonText
            if (showFailureReassurance) views += nfcResultReassuranceText
        }

        val reducedMotion = ReducedMotion.isEnabled(this)
        val offset = dpToPx(8f).toFloat()
        views.forEachIndexed { index, view ->
            view.animate().cancel()
            view.visibility = View.VISIBLE
            if (reducedMotion) {
                view.alpha = 1f
                view.translationY = 0f
                return@forEachIndexed
            }
            view.alpha = 0f
            view.translationY = offset
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(index * RESULT_TEXT_STAGGER_MS)
                .setDuration(RESULT_TEXT_IN_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun resetResultTextViews() {
        amountColorAnimator?.cancel()
        amountColorAnimator = null
        showFailureReassurance = false

        val textViews = listOf(
            animationResultAmountText,
            animationResultLabelText,
            nfcHintText,
            nfcResultReasonText,
            nfcResultReassuranceText,
        )
        textViews.forEach { view ->
            view.animate().cancel()
            view.alpha = 1f
            view.translationY = 0f
        }

        animationResultAmountText.visibility = View.INVISIBLE
        animationResultAmountText.text = ""
        animationResultAmountText.setTextColor(ContextCompat.getColor(this, R.color.color_text_primary))

        animationResultLabelText.visibility = View.INVISIBLE
        animationResultLabelText.text = ""
        nfcHintText.visibility = View.INVISIBLE
        nfcHintText.text = ""
        nfcResultReasonText.visibility = View.GONE
        nfcResultReasonText.text = ""
        nfcResultReassuranceText.visibility = View.GONE

        nfcLoader.animate().cancel()
        nfcLoader.visibility = View.INVISIBLE
        nfcLoader.alpha = 1f
        nfcLoader.scaleX = 1f
        nfcLoader.scaleY = 1f
    }

    private fun resetResultActionButtons() {
        currentOverlayActionMode = OverlayActionMode.SUCCESS
        animationActionsContainer.animate().cancel()
        // INVISIBLE rather than GONE keeps the content column from shifting when actions appear
        animationActionsContainer.visibility = View.INVISIBLE
        animationActionsContainer.alpha = 0f
        animationActionsContainer.translationY = 0f
        animationViewDetailsButton.isEnabled = false
        animationViewDetailsButton.text = getString(R.string.payment_received_button_view_details)
        animationCloseButton.isEnabled = false
        animationCloseButton.text = getString(R.string.payment_request_animation_close)
    }

    private fun isNightMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /**
     * Edge-to-edge bars over the overlay. Icons follow the theme on the app background and
     * turn light once the success green has been revealed.
     */
    private fun applyFullscreenForAnimationOverlay() {
        val darkIcons = !overlayOnSuccessColor && !isNightMode()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            show(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            isAppearanceLightStatusBars = darkIcons
            isAppearanceLightNavigationBars = darkIcons
        }
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
    }

    private fun restoreSystemBarsAfterAnimation() {
        overlayOnSuccessColor = false
        val darkIcons = !isNightMode()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            show(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
            isAppearanceLightStatusBars = darkIcons
            isAppearanceLightNavigationBars = darkIcons
        }
        ViewCompat.requestApplyInsets(findViewById(R.id.payment_request_root))
    }

    private fun adjustOverlayForInsets(insets: WindowInsetsCompat) {
        val systemInsets = insets.getInsetsIgnoringVisibility(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        if (nfcOverlayColumn.paddingTop != systemInsets.top) {
            nfcOverlayColumn.setPadding(0, systemInsets.top, 0, 0)
        }

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

        // Payment overlay motion
        private const val NFC_MIN_LOADING_MS = 600L
        private const val OVERLAY_FADE_IN_MS = 150L
        private const val HINT_FADE_OUT_MS = 120L
        private const val HINT_FADE_IN_MS = 200L
        private const val LOADER_EXIT_MS = 150L
        private const val LOADER_EXIT_SCALE = 0.9f
        private const val AMOUNT_RECOLOR_MS = 260L
        private const val AMOUNT_RECOLOR_DELAY_MS = 80L
        private const val RESULT_TEXT_IN_MS = 300L
        private const val RESULT_TEXT_STAGGER_MS = 60L
        private const val RESULT_ACTIONS_DELAY_MS = 120L
        private const val BTCPAY_INVOICE_TIMEOUT_MS = 15 * 60 * 1000L // 15 min
        private const val BTCPAY_MAX_POLL_ERRORS = 5



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
