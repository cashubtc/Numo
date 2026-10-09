package com.electricdreams.numo

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Intent
import android.content.ComponentName
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
import android.util.TypedValue
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import com.electricdreams.numo.core.dev.WalletLogger
import com.electricdreams.numo.core.network.ConnectivityMonitor
import com.electricdreams.numo.core.util.NetworkUtils
import com.electricdreams.numo.feature.offline.OfflineExplainerActivity
import com.electricdreams.numo.ui.offline.OfflineStripController
import com.electricdreams.numo.ui.offline.OfflineStripView
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.content.ContextCompat
import android.view.animation.DecelerateInterpolator
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
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
import com.electricdreams.numo.payment.UnifiedPaymentRequest
import com.electricdreams.numo.payment.ArkoorMintSession
import com.electricdreams.numo.payment.LightningMintHandler
import com.electricdreams.numo.payment.MintQuoteWebSocket
import com.electricdreams.numo.payment.NostrPaymentHandler
import com.electricdreams.numo.payment.PaymentIntentFactory
import com.electricdreams.numo.payment.PaymentTabManager
import com.electricdreams.numo.payment.PaymentWebhookDispatcher
import com.electricdreams.numo.ui.animation.NfcPaymentAnimationView
import com.electricdreams.numo.ui.util.QrCodeGenerator
import com.electricdreams.numo.feature.autowithdraw.AutoWithdrawManager
import com.electricdreams.numo.core.payment.BtcPayQrCodeBuilder
import com.electricdreams.numo.core.payment.IPaymentService
import com.electricdreams.numo.core.payment.PaymentServiceFactory
import com.electricdreams.numo.core.payment.PaymentState
import com.electricdreams.numo.core.payment.impl.BTCPayPaymentService
import com.electricdreams.numo.core.wallet.WalletError
import kotlinx.coroutines.CancellationException
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

    // HCE mode for deciding which payload to emulate
    private enum class HceMode { UNIFIED, CASHU, LIGHTNING }
    private enum class OverlayActionMode { SUCCESS, ERROR }

    private var paymentAmount: Long = 0
    private var paymentUnit: String = "sat"
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
    private var quoteSockets: MintQuoteWebSocket? = null
    private var arkoorJob: Job? = null
    private var isDualQuoteCheckout = false
    private var arkoorLoading = false
    private var arkoorAddress: String? = null
    private var arkoorMintUrl: String? = null
    private var resumeArkoorQuoteId: String? = null
    private var resumeArkoorMintUrl: String? = null
    private var lightningStarted = false

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
    private var pendingNfcSuccessMintUrl: String? = null
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
    private var animationAmountBaseTranslationY: Float = 0f
    private var animationLabelBaseTranslationY: Float = 0f
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
        animationResultAmountText = findViewById(R.id.animation_result_amount)
        animationResultLabelText = findViewById(R.id.animation_result_label)
        animationActionsContainer = findViewById(R.id.animation_actions_container)
        animationViewDetailsButton = findViewById(R.id.animation_view_details_button)
        animationCloseButton = findViewById(R.id.animation_close_button)
        animationAmountBaseTranslationY = animationResultAmountText.translationY
        animationLabelBaseTranslationY = animationResultLabelText.translationY

        setupNfcAnimationOverlay()
        observeConnectivityForPayment()

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
                    PaymentTabManager.PaymentTab.UNIFIED -> setHceToUnified()
                    PaymentTabManager.PaymentTab.LIGHTNING -> setHceToLightning()
                    PaymentTabManager.PaymentTab.CASHU -> setHceToCashu()
                }
                updatePaymentMethodStatus()
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
        resumeArkoorQuoteId = intent.getStringExtra(EXTRA_ARKOOR_QUOTE_ID)
        resumeArkoorMintUrl = intent.getStringExtra(EXTRA_ARKOOR_MINT_URL)
        initializePaymentUnit()

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
                PaymentTabManager.PaymentTab.UNIFIED -> unifiedPaymentRequest()
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

    private fun initializePaymentUnit() {
        // A checkout keeps its original unit even when the merchant changes preferences.
        paymentUnit = intent.getStringExtra(EXTRA_PAYMENT_UNIT)
            ?: pendingPaymentId?.let {
                PaymentsHistoryActivity.getPaymentEntryById(this, it)?.getUnit()
            }
            ?: if (resumeArkoorQuoteId != null) "sat" else MintManager.getInstance(this).getPreferredUnit()
    }

    private fun updateConvertedAmount(formattedAmountString: String) {
        val isCustomUnit = paymentUnit.lowercase() != "sat"
        
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

                showWaitingStatus()

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

                showWaitingStatus()
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
     * Local CDK checkout: Cashu plus independently quoted Lightning and Arkoor options.
     */
    private fun initializeLocalPaymentRequest() {
        // Get allowed mints supporting the active unit
        val mintManager = MintManager.getInstance(this)
        val activeUnit = paymentUnit
        val allowedMints = mintManager.getAllowedMints().filter { mintManager.mintSupportsUnit(it, activeUnit) }
        Log.d(TAG, "Using ${allowedMints.size} allowed mints for payment request")

        isDualQuoteCheckout = true
        arkoorLoading = true
        lightningStarted = true
        // Every checkout offers two independently quoted ways to pay the same amount.
        // Both start immediately, including when the delayed-Lightning preference is set.
        val preferredMint = resumeLightningMintUrl ?: resumeArkoorMintUrl
            ?: mintManager.getPreferredLightningMint(activeUnit)
        val sockets = MintQuoteWebSocket(lifecycleScope)
        quoteSockets = sockets
        lightningHandler = LightningMintHandler(
            this, preferredMint, allowedMints, lifecycleScope,
            quoteSockets = sockets, paymentUnit = activeUnit,
        )
        largeAmountDisplay.text = if (activeUnit == "sat") {
            Amount(paymentAmount, Currency.BTC).toString()
        } else {
            formattedAmountString
        }
        convertedAmountDisplay.visibility = View.GONE
        tabManager.selectTab(PaymentTabManager.PaymentTab.UNIFIED)
        startLightningMintFlow()
        startArkoorPaymentFlow()

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
                mintsForPaymentRequest,
                paymentUnit = activeUnit,
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
        nostrHandler = NostrPaymentHandler(this, allowedMints, paymentUnit = activeUnit)
        startNostrPaymentFlow()

    }

    private fun startArkoorPaymentFlow() {
        val manager = MintManager.getInstance(this)
        val mintUrl = resumeArkoorMintUrl ?: resumeLightningMintUrl
            ?: manager.getPreferredLightningMint(paymentUnit)
        if (mintUrl == null || paymentUnit != "sat") {
            onArkoorUnavailable(getString(R.string.payment_request_arkoor_requires_mint))
            return
        }
        // Display the saved destination while the session refreshes its status online.
        // Restoring a request does not establish that the checkout has been paid.
        val savedAddress = intent.getStringExtra(EXTRA_ARKOOR_ADDRESS)
        if (resumeArkoorQuoteId != null && resumeArkoorMintUrl != null &&
            savedAddress != null &&
            (savedAddress.startsWith("ark1") || savedAddress.startsWith("tark1"))) {
            arkoorAddress = savedAddress
            arkoorMintUrl = mintUrl
            arkoorLoading = false
            updateUnifiedQrCode()
        }
        arkoorJob = lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val repository = com.electricdreams.numo.core.cashu.CashuWalletManager.getWallet()
                        ?: error("Wallet not ready")
                    val wallet = repository.getWallet(
                        org.cashudevkit.MintUrl(mintUrl), org.cashudevkit.CurrencyUnit.Sat,
                    )
                    ArkoorMintSession(wallet, subscribeToQuote = { quoteId ->
                        checkNotNull(quoteSockets).subscribe(mintUrl, "arkoor_mint_quote", quoteId)
                    }).receive(
                        amountSats = paymentAmount,
                        existingQuoteId = resumeArkoorQuoteId,
                        onRequestReady = { quote ->
                            withContext(Dispatchers.Main) {
                                arkoorAddress = quote.request
                                arkoorMintUrl = mintUrl
                                arkoorLoading = false
                                pendingPaymentId?.let { id ->
                                    PaymentsHistoryActivity.updatePendingWithArkoorInfo(
                                        context = this@PaymentRequestActivity,
                                        paymentId = id,
                                        address = quote.request,
                                        quoteId = quote.id,
                                        mintUrl = mintUrl,
                                    )
                                }
                                updateUnifiedQrCode()
                            }
                        },
                        onRetry = { error ->
                            Log.w(TAG, "Arkoor quote check or issuance will retry", error)
                            withContext(Dispatchers.Main) {
                                if (tabManager.getCurrentTab() == PaymentTabManager.PaymentTab.UNIFIED) {
                                    statusText.setText(R.string.payment_request_arkoor_retrying)
                                }
                            }
                        },
                    )
                }
                handleLightningPaymentSuccess(PaymentHistoryEntry.TYPE_ARKOOR)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "Arkoor checkout failed", error)
                onArkoorUnavailable(getString(R.string.payment_request_arkoor_failed, error.message))
            }
        }
    }

    /**
     * Offline mid-payment: keep the QR up (an issued invoice can still be paid), drop the tap
     * prompt since ecash over NFC needs the mint, and swap "Waiting for payment..." for a line
     * that reassures the merchant.
     */
    private fun observeConnectivityForPayment() {
        val contactlessIcon = findViewById<ImageView>(R.id.contactless_icon)
        val instructionText = findViewById<TextView>(R.id.instruction_text)
        val iconGap = resources.getDimensionPixelSize(R.dimen.space_m)
        val offlinePill = findViewById<View>(R.id.offline_pill)
        offlinePill.setOnClickListener {
            OfflineExplainerActivity.start(this, paymentOnScreen = confirmsAfterReconnect())
        }
        ViewCompat.replaceAccessibilityAction(
            offlinePill,
            AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
            getString(R.string.offline_strip_action_details),
            null
        )
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    ConnectivityMonitor.getInstance(this@PaymentRequestActivity).isOnline.collect { online ->
                        contactlessIcon.visibility = if (online) View.VISIBLE else View.GONE
                        instructionText.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                            marginStart = if (online) iconGap else 0
                        }
                        instructionText.setText(
                            if (online) R.string.payment_request_instruction_scan_or_tap
                            else R.string.payment_request_instruction_scan
                        )
                        refreshWaitingStatus()
                    }
                }
                launch { OfflineStripController.stripMode.collect(::renderOfflinePill) }
            }
        }
    }

    private fun refreshWaitingStatus() {
        // Preserve errors and results while updating either checkout's waiting line.
        val current = statusText.text.toString()
        if (current == getString(R.string.payment_request_status_waiting_for_payment) ||
            current == getString(R.string.payment_request_offline_note) ||
            (isDualQuoteCheckout && current == paymentMethodsLabel())) {
            if (isDualQuoteCheckout) updatePaymentMethodStatus() else showWaitingStatus()
        }
    }

    /** "Waiting for payment...", or the offline reassurance while there's no connection. */
    private fun showWaitingStatus() {
        val offline = !NetworkUtils.isNetworkAvailable(this)
        statusText.text = getString(
            if (offline && confirmsAfterReconnect()) R.string.payment_request_offline_note
            else R.string.payment_request_status_waiting_for_payment
        )
    }

    /**
     * Whether a payment made while offline is picked up on reconnect: the local wallet keeps
     * polling its mint quote and re-subscribes to Nostr. BTCPay polling gives up after repeated
     * errors, so it makes no such promise.
     */
    private fun confirmsAfterReconnect(): Boolean =
        !(::paymentService.isInitialized && paymentService is BTCPayPaymentService)

    /** The header's stand-in for the app-wide strip, which this screen opts out of. */
    private fun renderOfflinePill(mode: OfflineStripView.Mode?) {
        val pill = findViewById<View>(R.id.offline_pill)
        val text = findViewById<TextView>(R.id.offline_pill_text)
        pill.animate().cancel()
        if (mode == null) {
            if (pill.visibility != View.VISIBLE) return
            pill.animate().alpha(0f).scaleX(PILL_HIDDEN_SCALE).scaleY(PILL_HIDDEN_SCALE)
                .setDuration(PILL_OUT_MS)
                .withEndAction { pill.visibility = View.GONE }
                .start()
            return
        }
        val offline = mode == OfflineStripView.Mode.OFFLINE
        val color = ContextCompat.getColor(
            this,
            if (offline) R.color.color_warning else R.color.color_success_green
        )
        text.setText(if (offline) R.string.offline_strip_title else R.string.offline_strip_back_online_title)
        text.setTextColor(color)
        pill.isClickable = offline
        pill.isFocusable = offline
        if (pill.visibility != View.VISIBLE) {
            pill.alpha = 0f
            pill.scaleX = PILL_HIDDEN_SCALE
            pill.scaleY = PILL_HIDDEN_SCALE
            pill.visibility = View.VISIBLE
        }
        pill.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(PILL_IN_MS)
            .setInterpolator(DecelerateInterpolator(2f))
            .start()
    }

    private fun onArkoorUnavailable(message: String) {
        if (hasTerminalOutcome) return
        arkoorLoading = false
        arkoorAddress = null
        updateUnifiedQrCode()
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    /** Poll BTCPay invoice status every 2 seconds until terminal state. */
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
        val payload = unifiedPaymentRequest() ?: return

        try {
            val hceService = NdefHostCardEmulationService.getInstance()
            if (hceService != null) {
                Log.d(TAG, "setHceToUnified(): Switching HCE payload to Unified. payload=$payload")
                hceService.setPaymentRequest(payload, paymentAmount)
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

    private fun unifiedPaymentRequest(): String? {
        val creq = nostrHandler?.paymentRequestBech32 ?: hcePaymentRequestBech32
            ?: btcPayCashuPRBech32 ?: btcPayCashuPR ?: hcePaymentRequest
        return UnifiedPaymentRequest(
            amountSats = paymentAmount,
            cashu = creq,
            lightning = lightningInvoice,
            ark = arkoorAddress,
            lightningPending = lightningStarted && lightningInvoice == null,
            arkoorPending = arkoorLoading,
        ).toUri()
    }

    private fun updatePaymentMethodStatus() {
        if (hasTerminalOutcome || !isDualQuoteCheckout) return
        statusText.visibility = View.VISIBLE
        if (!NetworkUtils.isNetworkAvailable(this) && confirmsAfterReconnect()) {
            showWaitingStatus()
        } else if (tabManager.getCurrentTab() == PaymentTabManager.PaymentTab.UNIFIED &&
            unifiedPaymentRequest() != null) {
            statusText.text = paymentMethodsLabel()
        } else {
            showWaitingStatus()
        }
    }

    private fun paymentMethodsLabel(): String = listOfNotNull(
        "Cashu".takeIf { nostrHandler?.paymentRequestBech32 != null || hcePaymentRequest != null },
        "Lightning".takeIf { lightningInvoice != null },
        getString(R.string.payment_request_arkoor).takeIf { arkoorAddress != null },
    ).joinToString(" · ")

    private fun updateUnifiedQrCode() {
        if (hasTerminalOutcome) return
        val unifiedUri = unifiedPaymentRequest() ?: return
        try {
            // Square modules keep this denser, multi-method QR readable.
            unifiedQrImageView.setImageBitmap(
                QrCodeGenerator.generate(unifiedUri, 1024, roundedDots = false),
            )
            unifiedQrImageView.visibility = View.VISIBLE
            unifiedLoadingSpinner.visibility = View.GONE
            updatePaymentMethodStatus()
            if (tabManager.getCurrentTab() == PaymentTabManager.PaymentTab.UNIFIED) {
                setHceToUnified()
            }
        } catch (error: Exception) {
            Log.e(TAG, "Error generating unified payment QR", error)
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
                    updatePaymentMethodStatus()
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
        val handler = lightningHandler ?: return
        lightningStarted = true

        val quoteId = resumeLightningQuoteId
        val mintUrl = resumeLightningMintUrl
        val invoice = resumeLightningInvoice
        if (quoteId != null && mintUrl != null && invoice != null) {
            handler.resume(quoteId, mintUrl, invoice, createLightningCallback())
        } else {
            handler.start(paymentAmount, createLightningCallback())
        }
    }

    private fun createLightningCallback(): LightningMintHandler.Callback {
        return object : LightningMintHandler.Callback {
            override fun onInvoiceReady(bolt11: String, quoteId: String, mintUrl: String) {
                if (hasTerminalOutcome) return
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
                if (hasTerminalOutcome) return
                // Hide loading spinner on error
                lightningLoadingSpinner.visibility = View.GONE
                lightningStarted = false // Other available methods can still be offered.
                updateUnifiedQrCode()
                
                // Keep Cashu and Arkoor available when Lightning cannot create its quote.
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
                                    unit = paymentUnit,
                                )

                                val mintManager = MintManager.getInstance(this@PaymentRequestActivity)
                                val activeUnit = paymentUnit
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
                                handlePaymentError(getString(R.string.payment_failure_button_try_again))
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
            PaymentsHistoryActivity.completePendingPayment(
                context = this,
                paymentId = paymentId,
                token = token,
                paymentType = PaymentHistoryEntry.TYPE_CASHU,
                paymentRequest = nostrHandler?.paymentRequestBech32 ?: hcePaymentRequestBech32
                    ?: btcPayCashuPRBech32 ?: btcPayCashuPR ?: hcePaymentRequest,
                mintUrl = mintUrl,
                lightningInvoice = lightningInvoice,
                lightningQuoteId = lightningQuoteId,
                lightningMintUrl = lightningMintUrl,
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

        val paidMint = if (paymentType == PaymentHistoryEntry.TYPE_ARKOOR) {
            arkoorMintUrl
        } else lightningMintUrl
        WalletLogger.log("IN", paymentAmount, paidMint ?: "Unknown", "$paymentType payment successful")

        statusText.visibility = View.VISIBLE
        statusText.text = getString(R.string.payment_request_status_success)

        // Update pending payment to completed with Lightning info
        pendingPaymentId?.let { paymentId ->
            PaymentsHistoryActivity.completePendingPayment(
                context = this,
                paymentId = paymentId,
                token = "",
                paymentType = paymentType,
                mintUrl = paidMint,
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

        showPaymentSuccess("", paymentAmount, paidMint)
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

        arkoorJob?.cancel()
        lightningHandler?.cancel()
        quoteSockets?.close()

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

        // ALWAYS render the native failure overlay (the ALL RED screen) so that failure paths stay consistent,
        // mirroring the behavior of showPaymentSuccess.
        if (nfcAnimationContainer.visibility != View.VISIBLE) {
            nfcOverlayShownAtMs = SystemClock.elapsedRealtime()
            applyFullscreenForAnimationOverlay()
            nfcAnimationContainer.visibility = View.VISIBLE
            nfcAnimationView.reset()
            resetResultTextViews()
            resetResultActionButtons()
            ViewCompat.requestApplyInsets(nfcAnimationContainer)
        } else {
            applyFullscreenForAnimationOverlay()
        }

        showNfcAnimationError(errorMessage)
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
        arkoorJob?.cancel()
        lightningHandler?.cancel()
        lightningHandler = null
        quoteSockets?.close()
        quoteSockets = null

        // Clean up HCE service
        clearHceService()

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
        btcPayPollingJob?.cancel()
        btcPayPollingJob = null
        nostrHandler?.stop()
        nostrHandler = null
        arkoorJob?.cancel()
        lightningHandler?.cancel()
        lightningHandler = null
        quoteSockets?.close()
        quoteSockets = null

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
    private fun triggerPostPaymentOperations(token: String, receivedMintUrl: String?) {
        // Archive the basket now that payment is complete
        markBasketAsPaid()
        
        // Check for auto-withdrawal after successful payment (runs in background, survives activity destruction)
        AutoWithdrawManager.getInstance(this).onPaymentReceived(token, receivedMintUrl)
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
    private fun showPaymentSuccess(token: String, amount: Long, receivedMintUrl: String? = null) {
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
        pendingNfcSuccessMintUrl = receivedMintUrl
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
        arkoorJob?.cancel()
        lightningHandler?.cancel()
        lightningHandler = null
        quoteSockets?.close()
        quoteSockets = null

        // Clean up HCE service
        clearHceService()

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
        pendingNfcSuccessMintUrl = null
        pendingNfcSuccessAmount = 0

        animationResultLabelText.animate().cancel()

        // Show "Keep phone close" hint during NFC communication
        animationResultLabelText.visibility = View.VISIBLE
        animationResultLabelText.alpha = 0.75f
        animationResultLabelText.translationY = animationLabelBaseTranslationY
        animationResultLabelText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
        animationResultLabelText.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        animationResultLabelText.gravity = android.view.Gravity.CENTER
        animationResultLabelText.textAlignment = View.TEXT_ALIGNMENT_CENTER
        animationResultLabelText.letterSpacing = 0.03f
        animationResultLabelText.text = getString(R.string.nfc_payment_hint_keep_close)
        
        nfcAnimationView.reset()
        nfcAnimationView.startReading()
        nfcAnimationStartedAtMs = SystemClock.elapsedRealtime()
        Log.d(TAG, "animation_started_ms=${nfcAnimationStartedAtMs - nfcOverlayShownAtMs}")
        startNfcSafetyTimeout()
        ViewCompat.requestApplyInsets(nfcAnimationContainer)
    }

    private fun showNfcAnimationProcessing() {
        if (nfcAnimationContainer.visibility != View.VISIBLE) return
        
        // Vibrate when switching to processing phase
        try {
            val vibrator = getVibrator()
            vibrator?.vibrateCompat(longArrayOf(0, 50), -1)
        } catch (_: Exception) {}
        
        // Show "Processing... You can lift your phone" hint with a gentle crossfade
        animationResultLabelText.animate().cancel()
        animationResultLabelText.animate()
            .alpha(0f)
            .setDuration(150)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                animationResultLabelText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
                animationResultLabelText.gravity = android.view.Gravity.CENTER
                animationResultLabelText.textAlignment = View.TEXT_ALIGNMENT_CENTER
                animationResultLabelText.text = getString(R.string.nfc_payment_hint_processing)
                animationResultLabelText.animate()
                    .alpha(0.75f)
                    .setDuration(200)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            }
            .start()
        
        nfcAnimationView.startProcessing()
    }

    private fun hideNfcAnimationOverlay() {
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
        nfcAnimationTimeoutRunnable?.let {
            Log.d(TAG, "Cancelling Activity NFC safety timeout")
            nfcTimeoutHandler.removeCallbacks(it)
        }
        nfcAnimationTimeoutRunnable = null
    }

    private fun showNfcAnimationSuccess(amountText: String) {
        if (nfcAnimationContainer.visibility != View.VISIBLE) return

        animationResultLabelText.animate().cancel()

        currentOverlayActionMode = OverlayActionMode.SUCCESS
        animationResultAmountText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 56f)
        
        // Reset to bold/stronger style for success title
        animationResultLabelText.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        animationResultLabelText.letterSpacing = 0f
        animationResultLabelText.alpha = 1f
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
        val vibrator = getVibrator()
        vibrator?.vibrateCompat(longArrayOf(0, 50, 100, 50), -1)
    }

    private fun showNfcAnimationError(message: String) {
        if (nfcAnimationContainer.visibility != View.VISIBLE) return

        animationResultLabelText.animate().cancel()

        currentOverlayActionMode = OverlayActionMode.ERROR
        
        // Reset to bold/stronger style for error title
        animationResultLabelText.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        animationResultLabelText.letterSpacing = 0f
        animationResultLabelText.alpha = 1f
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

        if (success) pendingNfcSuccessToken?.let { token ->
            val receivedMintUrl = pendingNfcSuccessMintUrl
            pendingNfcSuccessToken = null
            pendingNfcSuccessMintUrl = null
            pendingNfcSuccessAmount = 0

            // Trigger auto-withdrawal and basket archiving (same as showPaymentSuccess does)
            // but don't show PaymentReceivedActivity since we're already showing animation
            triggerPostPaymentOperations(token, receivedMintUrl)
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
        animationResultAmountText.animate().cancel()
        animationResultAmountText.visibility = View.GONE
        animationResultAmountText.alpha = 0f
        animationResultAmountText.translationY = animationAmountBaseTranslationY
        animationResultAmountText.text = ""

        animationResultLabelText.animate().cancel()
        animationResultLabelText.visibility = View.GONE
        animationResultLabelText.alpha = 0f
        animationResultLabelText.translationY = animationLabelBaseTranslationY
        animationResultLabelText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
        animationResultLabelText.typeface = android.graphics.Typeface.DEFAULT
        animationResultLabelText.letterSpacing = 0f
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
        animationActionsContainer.animate().cancel()
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
            show(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
    }

    private fun restoreSystemBarsAfterAnimation() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            show(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        ViewCompat.requestApplyInsets(findViewById(R.id.payment_request_root))
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
        private const val BTCPAY_INVOICE_TIMEOUT_MS = 15 * 60 * 1000L // 15 min
        private const val BTCPAY_MAX_POLL_ERRORS = 5
        private const val PILL_IN_MS = 220L
        private const val PILL_OUT_MS = 160L
        private const val PILL_HIDDEN_SCALE = 0.92f



        const val EXTRA_PAYMENT_AMOUNT = "payment_amount"
        const val EXTRA_PAYMENT_UNIT = "payment_unit"
        const val EXTRA_FORMATTED_AMOUNT = "formatted_amount"
        const val RESULT_EXTRA_TOKEN = "payment_token"
        const val RESULT_EXTRA_AMOUNT = "payment_amount"

        // Extras for resuming pending payments
        const val EXTRA_RESUME_PAYMENT_ID = "resume_payment_id"
        const val EXTRA_LIGHTNING_QUOTE_ID = "lightning_quote_id"
        const val EXTRA_LIGHTNING_MINT_URL = "lightning_mint_url"
        const val EXTRA_LIGHTNING_INVOICE = "lightning_invoice"
        const val EXTRA_ARKOOR_ADDRESS = "arkoor_address"
        const val EXTRA_ARKOOR_QUOTE_ID = "arkoor_quote_id"
        const val EXTRA_ARKOOR_MINT_URL = "arkoor_mint_url"
        const val EXTRA_NOSTR_SECRET_HEX = "nostr_secret_hex"
        const val EXTRA_NOSTR_NPROFILE = "nostr_nprofile"

        // Extra for checkout basket data
        const val EXTRA_CHECKOUT_BASKET_JSON = "checkout_basket_json"
        
        // Extra for saved basket ID
        const val EXTRA_SAVED_BASKET_ID = "saved_basket_id"
    }
}
