package com.electricdreams.numo

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Intent
import android.content.ComponentName
import android.content.res.Configuration
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
import android.Manifest
import android.content.pm.PackageManager
import android.widget.ImageView
import android.view.animation.PathInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicatorSpec
import com.google.android.material.progressindicator.IndeterminateDrawable
import com.stripe.stripeterminal.external.models.PaymentIntent
import com.stripe.stripeterminal.external.models.TerminalErrorCode
import com.stripe.stripeterminal.external.models.TerminalException
import androidx.lifecycle.lifecycleScope
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
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
import com.electricdreams.numo.payment.card.CardAmountResolver
import com.electricdreams.numo.payment.card.StripeCardSettings
import com.electricdreams.numo.payment.card.StripeTerminalManager
import com.electricdreams.numo.ui.animation.NfcPaymentAnimationView
import com.electricdreams.numo.ui.util.QrCodeGenerator
import com.electricdreams.numo.ui.util.isAnimationEnabled
import com.electricdreams.numo.ui.util.shake
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

import com.electricdreams.numo.core.update.holdPaymentScreen
import com.electricdreams.numo.core.update.launchPaymentOperation

class PaymentRequestActivity : AppCompatActivity() {

    private lateinit var unifiedQrImageView: ImageView
    private lateinit var cashuQrImageView: ImageView
    private lateinit var lightningQrImageView: ImageView
    private lateinit var unifiedQrContainer: View
    private lateinit var cashuQrContainer: View
    private lateinit var lightningQrContainer: View
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

    // Card (Stripe Tap to Pay) views and state
    private lateinit var cardPayButton: MaterialButton
    private lateinit var cardMessageText: TextView
    private var cardAmount: CardAmountResolver.CardAmount? = null
    private var cardJob: Job? = null

    /** True while the phone is reading a card, when it must not also emulate a payment tag. */
    private var isCardCollecting = false

    /** True once Stripe's Tap to Pay screen has been asked to open over checkout. */
    private var isCardTapScreenLaunched = false

    /** True while a neutral tap surface covers checkout underneath Stripe's Tap to Pay screen. */
    private var isCardSurfaceCovered = false

    /** True while Numo's own waiting screen covers checkout as Stripe confirms the charge. */
    private var isCardConfirming = false

    /** True while the tap waiting screen has the status bar hidden. */
    private var isStatusBarHiddenForWaiting = false

    /** Tracks whether the overlay is currently on the light/dark tap surface or full-bleed result colour. */
    private enum class OverlayBarStyle { TAP_SURFACE, RESULT_COLOR }
    private var currentOverlayBarStyle: OverlayBarStyle = OverlayBarStyle.TAP_SURFACE

    /** Tracks whether the unified QR has already revealed once so only the first reveal fades in. */
    private var hasShownUnifiedQr = false

    /** True when the last attempt's outcome is unknown and the next tap checks on it. */
    private var cardNeedsStatusCheck = false

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            startCardPayment()
        } else {
            showCardIdle(getString(R.string.card_payment_status_location_required), showRetry = true)
        }
    }

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
    private var arkoorError: String? = null
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
    private var hceReadyListener: NdefHostCardEmulationService.ReadyListener? = null

    private val uiScope = CoroutineScope(Dispatchers.Main)

    // NFC animation timing diagnostics
    private var nfcOverlayShownAtMs: Long = 0
    private var nfcAnimationStartedAtMs: Long = 0
    private var resultTextAnimator: AnimatorSet? = null

    /** Dedicated waiting/processing hint anchored below the disc; nullable for unit tests. */
    private var animationWaitingHintText: TextView? = null

    /** Dedicated result amount + caption stack anchored below the result circle; nullable for unit tests. */
    private var animationResultTextContainer: View? = null

    /** The amount under "Total" on the waiting screen; absent until the layout is bound. */
    private var animationTotalContainer: View? = null
    private var animationTotalAmountText: TextView? = null

    /** Material's emphasized-decelerate curve, for everything that enters the result screen. */
    private val resultEnterInterpolator = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    private var nfcAnimationTimeoutRunnable: Runnable? = null
    private val nfcTimeoutHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!holdPaymentScreen()) return
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_payment_request)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }

        // Apply window insets using getInsetsIgnoringVisibility so hiding the status bar during
        // tap-to-pay never shifts the QR code or top chrome underneath the overlay.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.payment_request_root)) { v, windowInsets ->
            val insets = windowInsets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())

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

            findViewById<View>(R.id.card_pay_button).layoutParams =
                (findViewById<View>(R.id.card_pay_button).layoutParams as MarginLayoutParams).apply {
                    bottomMargin = insets.bottom + bottomMarginPx
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
        cardPayButton = findViewById(R.id.card_pay_button)
        cardMessageText = findViewById(R.id.card_message_text)
        cardPayButton.setOnClickListener { startCardPayment() }

        // NFC Animation views
        nfcAnimationContainer = findViewById(R.id.nfc_animation_container)
        nfcAnimationView = findViewById(R.id.nfc_animation_view)
        animationWaitingHintText = findViewById(R.id.animation_waiting_hint)
        animationResultTextContainer = findViewById(R.id.animation_result_text_container)
        animationResultAmountText = findViewById(R.id.animation_result_amount)
        animationResultLabelText = findViewById(R.id.animation_result_label)
        animationActionsContainer = findViewById(R.id.animation_actions_container)
        animationViewDetailsButton = findViewById(R.id.animation_view_details_button)
        animationCloseButton = findViewById(R.id.animation_close_button)
        animationTotalContainer = findViewById(R.id.animation_total_container)
        animationTotalAmountText = findViewById(R.id.animation_total_amount)

        setupNfcAnimationOverlay()
        observeConnectivityForPayment()

        // Initialize tab manager
        tabManager = PaymentTabManager(
            unifiedQrContainer = unifiedQrContainer,
            cashuQrContainer = cashuQrContainer,
            lightningQrContainer = lightningQrContainer,
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

        // Offer card payments when the merchant has set up Stripe on a supported phone
        setupCardPayments()


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
        if (hasTerminalOutcome) {
            animateSuccessScreenOut()
            return
        }
        // Dismiss an active waiting tap screen back to the QR without cancelling the sale.
        if (::nfcAnimationContainer.isInitialized &&
            nfcAnimationContainer.visibility == View.VISIBLE &&
            !isProcessingNfcPayment &&
            !isCardConfirming &&
            !isCardSurfaceCovered
        ) {
            hideNfcAnimationOverlay()
            return
        }
        cancelPayment()
        super.onBackPressed()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::nfcAnimationContainer.isInitialized && nfcAnimationContainer.visibility == View.VISIBLE) {
            val waiting = isStatusBarHiddenForWaiting
            applyFullscreenForAnimationOverlay(onResultColor = currentOverlayBarStyle == OverlayBarStyle.RESULT_COLOR)
            if (waiting) hideStatusBarWhileWaiting()
        }
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing) return
        // Safety net: if Stripe returned without triggering a card outcome, drop the neutral cover.
        if (isCardSurfaceCovered && !isCardCollecting && !isCardConfirming && !hasTerminalOutcome) {
            isCardSurfaceCovered = false
            hideNfcAnimationOverlay()
        }
        if (!isCardCollecting && !isCardConfirming) {
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
    }

    override fun onPause() {
        clearPendingHceReadyListener()
        maybeParkCardSurfaceCoverOnPause()
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

        uiScope.launchPaymentOperation {
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
        uiScope.launchPaymentOperation {
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
        uiScope.launchPaymentOperation {
            for (attempt in 1..10) {
                delay(1500)
                if (hasTerminalOutcome) break
                val bolt11 = btcPay.fetchLightningInvoice(invoiceId)
                if (bolt11 != null) {
                    Log.d(TAG, "Got bolt11 in background after $attempt attempt(s)")
                    showBtcPayLightningQr(bolt11)
                    return@launchPaymentOperation
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
        // Offer independently quoted methods when the selected mint supports them.
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
            onArkoorUnavailable()
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
        arkoorJob = lifecycleScope.launchPaymentOperation {
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
            } catch (unsupported: ArkoorMintSession.UnsupportedMintException) {
                Log.d(TAG, "Selected mint does not offer Arkoor in sats")
                onArkoorUnavailable()
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
        val instructionText = findViewById<TextView>(R.id.instruction_text)
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
        text.setText(mode.title)
        text.setTextColor(ContextCompat.getColor(this, mode.color))
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

    private fun onArkoorUnavailable(message: String? = null) {
        if (hasTerminalOutcome) return
        arkoorLoading = false
        arkoorAddress = null
        arkoorError = message
        updateUnifiedQrCode()
    }

    /** Poll BTCPay invoice status every 2 seconds until terminal state. */
    private fun startBtcPayPolling(paymentId: String) {
        btcPayPollingJob = uiScope.launchPaymentOperation {
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
                    return@launchPaymentOperation
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
        if (isCardCollecting) return
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
        if (isCardCollecting) return
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
        if (isCardCollecting) return
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
            statusText.text = listOfNotNull(paymentMethodsLabel(), arkoorError).joinToString("\n")
        } else {
            showWaitingStatus()
        }
    }

    private fun paymentMethodsLabel(): String = listOfNotNull(
        "Cashu".takeIf { nostrHandler?.paymentRequestBech32 != null || hcePaymentRequest != null },
        "Lightning".takeIf { lightningInvoice != null },
        getString(R.string.payment_request_arkoor).takeIf { arkoorAddress != null },
    ).joinToString(", ")

    private fun updateUnifiedQrCode() {
        if (hasTerminalOutcome) return
        val unifiedUri = unifiedPaymentRequest() ?: return
        try {
            // Square modules keep this denser, multi-method QR readable.
            unifiedQrImageView.setImageBitmap(
                QrCodeGenerator.generate(unifiedUri, 1024, roundedDots = false),
            )
            val firstReveal = !hasShownUnifiedQr
            hasShownUnifiedQr = true
            if (firstReveal && unifiedQrImageView.isAttachedToWindow && isAnimationEnabled()) {
                unifiedQrImageView.animate().cancel()
                unifiedQrImageView.alpha = 0f
                unifiedQrImageView.visibility = View.VISIBLE
                unifiedQrImageView.animate()
                    .alpha(1f)
                    .setDuration(QR_REVEAL_MS)
                    .setInterpolator(resultEnterInterpolator)
                    .start()
                unifiedLoadingSpinner.animate().cancel()
                unifiedLoadingSpinner.animate()
                    .alpha(0f)
                    .setDuration(QR_SPINNER_FADE_MS)
                    .withEndAction {
                        unifiedLoadingSpinner.visibility = View.GONE
                        unifiedLoadingSpinner.alpha = 1f
                    }
                    .start()
            } else {
                unifiedQrImageView.visibility = View.VISIBLE
                unifiedQrImageView.alpha = 1f
                unifiedLoadingSpinner.visibility = View.GONE
            }
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

    /** Offers the current bitcoin payment request over NFC. */
    private fun publishHcePaymentRequest() {
        when (tabManager.getCurrentTab()) {
            PaymentTabManager.PaymentTab.LIGHTNING ->
                if (lightningInvoice != null) setHceToLightning() else setHceToCashu()
            PaymentTabManager.PaymentTab.UNIFIED ->
                if (unifiedPaymentRequest() != null) setHceToUnified() else setHceToCashu()
            PaymentTabManager.PaymentTab.CASHU -> setHceToCashu()
        }
    }

    private fun clearPendingHceReadyListener() {
        hceReadyListener?.let { NdefHostCardEmulationService.clearReadyListener(it) }
        hceReadyListener = null
    }

    private fun setupNdefPayment() {
        if (hcePaymentRequest == null) return

        clearPendingHceReadyListener()
        val listener = NdefHostCardEmulationService.ReadyListener { hceService ->
            hceReadyListener = null
            if (isFinishing || isDestroyed || hasTerminalOutcome) return@ReadyListener
            Log.d(TAG, "Setting up NDEF payment with HCE service")

            if (isCardCollecting || isCardConfirming || isProcessingNfcPayment) {
                // Never emulate a payment tag while reading a card or redeeming an NFC token.
                hceService.clearPaymentRequest()
            } else {
                publishHcePaymentRequest()
            }

            // Set up callback for when a token is received or an error occurs
            hcePaymentCallback = object : NdefHostCardEmulationService.CashuPaymentCallback {
                override fun onCashuTokenReceived(token: String) {
                    // Raw Cashu token received over NFC. Delegate full
                    // validation, swap-to-Lightning-mint (if needed),
                    // and redemption to CashuPaymentHelper.
                    uiScope.launchPaymentOperation {
                        // Check if we are already processing a payment to avoid double-processing
                        if (isProcessingNfcPayment) {
                            Log.d(TAG, "NFC token received but ignored - already processing a payment")
                            return@launchPaymentOperation
                        }
                        
                        // Mark as processing immediately and stop HCE so trailing APDUs while
                        // lifting the phone cannot re-arm HCE timeouts during network redemption.
                        isProcessingNfcPayment = true
                        NdefHostCardEmulationService.getInstance()?.clearPaymentRequest()

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
                                    return@launchPaymentOperation
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
                        if (nfcAnimationContainer.visibility == View.VISIBLE && !isCardSurfaceCovered) {
                            Log.d(TAG, "NFC reading resumed on active overlay - refreshing hint")
                            keepOverlayShown()
                            swapWaitingHint(getString(R.string.nfc_payment_hint_keep_close))
                            startNfcSafetyTimeout()
                            return@runOnUiThread
                        }
                        Log.d(TAG, "NFC reading started - showing animation overlay")
                        showNfcAnimationOverlay()
                    }
                }

                override fun onNfcReadingStopped(failedInMiddleOfTransaction: Boolean) {
                    runOnUiThread {
                        handleNfcReadingStopped(failedInMiddleOfTransaction)
                    }
                }
            }
            hceService.setPaymentCallback(hcePaymentCallback)

            Log.d(TAG, "NDEF payment service ready")
        }
        hceReadyListener = listener
        NdefHostCardEmulationService.runWhenReady(listener)
    }

    /** Handles an NFC link drop or timeout without ever exposing QR during active processing. */
    private fun handleNfcReadingStopped(failedInMiddleOfTransaction: Boolean) {
        Log.w(TAG, "NFC reading stopped callback received (failedInMiddleOfTransaction: $failedInMiddleOfTransaction)")
        if (hasTerminalOutcome || isProcessingNfcPayment) {
            Log.d(TAG, "NFC reading stopped ignored - already processing or terminal outcome set")
            return
        }

        if (failedInMiddleOfTransaction) {
            Log.w(TAG, "NFC connection lost mid-write - prompting customer to tap again")
            showRecoverableTapLost()
        } else {
            Log.d(TAG, "NFC connection stopped without writing data. Returning to payment request screen.")
            hideNfcAnimationOverlay()
        }
    }

    // ============================================================
    // Card payments (Stripe Tap to Pay)
    // ============================================================

    /** Offers the card button when Stripe is configured, the device qualifies, and the unit is sats. */
    private fun setupCardPayments() {
        val available = paymentUnit.equals("sat", ignoreCase = true) &&
            StripeTerminalManager.isDeviceSupported(this) &&
            StripeCardSettings.getInstance(this).isReady()
        val visibility = if (available) View.VISIBLE else View.GONE
        cardPayButton.visibility = visibility
        cardMessageText.visibility = visibility
        if (available) resolveCardAmount()
    }

    /**
     * Fixes the card charge for this checkout. Retried on each attempt because a sats-priced
     * sale needs the bitcoin price, which may still be loading when the screen opens.
     */
    private fun resolveCardAmount(): CardAmountResolver.CardAmount? {
        cardAmount?.let { return it }
        val currencyCode = CurrencyManager.getInstance(this).getCurrentCurrency()
        val enteredAmount = Amount.parse(formattedAmountString, Amount.Currency.fromCode(currencyCode))
        cardAmount = CardAmountResolver.resolve(
            enteredAmount = enteredAmount,
            amountSats = paymentAmount,
            currencyCode = currencyCode,
            btcPrice = bitcoinPriceWorker?.getCurrentPrice() ?: 0.0,
        )
        updateCardButtonLabel()
        return cardAmount
    }

    /** The amount is already shown above, so the button only names the action. */
    private fun updateCardButtonLabel() {
        cardPayButton.text = getString(
            if (cardNeedsStatusCheck) R.string.card_payment_action_check else R.string.card_payment_button
        )
    }

    private fun formatCardAmount(amount: CardAmountResolver.CardAmount): String = try {
        val currency = java.util.Currency.getInstance(amount.currency.uppercase(java.util.Locale.US))
        val digits = currency.defaultFractionDigits.coerceAtLeast(0)
        java.text.NumberFormat.getCurrencyInstance().apply {
            this.currency = currency
            minimumFractionDigits = digits
            maximumFractionDigits = digits
        }.format(java.math.BigDecimal.valueOf(amount.minorUnits).movePointLeft(digits))
    } catch (e: IllegalArgumentException) {
        "${amount.minorUnits} ${amount.currency.uppercase(java.util.Locale.US)}"
    }

    /**
     * Stops card collection unless Stripe is already charging the card. A charge in flight is
     * left to finish so that it is recorded rather than lost.
     */
    private fun cancelCardCollection() {
        val job = cardJob ?: return
        if (StripeTerminalManager.isCharging) {
            Log.w(TAG, "Card is being charged; letting it finish so the payment is recorded")
            return
        }
        job.cancel()
        cardJob = null
    }

    private fun savedCardPaymentReference(): StripeTerminalManager.CardPaymentReference? {
        val paymentId = pendingPaymentId ?: return null
        val entry = PaymentsHistoryActivity.getPaymentEntryById(this, paymentId) ?: return null
        val clientSecret = entry.cardPaymentClientSecret ?: return null
        return StripeTerminalManager.CardPaymentReference(
            paymentIntentId = entry.cardPaymentIntentId,
            clientSecret = clientSecret,
        )
    }

    private fun persistCardPaymentIntent(paymentIntent: PaymentIntent) {
        val paymentId = pendingPaymentId
        val clientSecret = paymentIntent.clientSecret
        val saved = paymentId != null && clientSecret != null &&
            PaymentsHistoryActivity.updatePendingWithCardPaymentAttempt(
                context = this,
                paymentId = paymentId,
                paymentIntentId = paymentIntent.id,
                clientSecret = clientSecret,
            )
        if (!saved) {
            throw StripeTerminalManager.CardPaymentException(
                "Could not save the card payment attempt for recovery",
                isCancellation = false,
            )
        }
    }

    private fun startCardPayment() {
        if (hasTerminalOutcome || cardJob?.isActive == true) return

        val amount = resolveCardAmount() ?: run {
            val currencyCode = CurrencyManager.getInstance(this).getCurrentCurrency()
            showCardIdle(
                getString(R.string.card_payment_status_amount_unavailable, currencyCode),
                showRetry = true,
            )
            return
        }
        val config = StripeCardSettings.getInstance(this).loadConfig() ?: run {
            showCardIdle(getString(R.string.card_payment_status_not_configured), showRetry = false)
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            )
            return
        }

        // Runs outside lifecycleScope: a charge Stripe is confirming must still be recorded
        // if this screen closes. cancelCardCollection() stops it until that point.
        cardJob = StripeTerminalManager.paymentScope.launchPaymentOperation {
            showCardBusy()
            try {
                StripeTerminalManager.ensureReaderConnected(this@PaymentRequestActivity, config)
                val paymentIntent = StripeTerminalManager.collectPayment(
                    context = this@PaymentRequestActivity,
                    amount = amount,
                    description = getString(R.string.app_name),
                    metadata = buildMap {
                        pendingPaymentId?.let { put("numo_payment_id", it) }
                        put("numo_amount_sats", paymentAmount.toString())
                    },
                    previous = savedCardPaymentReference(),
                    onPaymentIntent = ::persistCardPaymentIntent,
                    onCollectionStarting = ::onCardCollectionStarting,
                    onCharging = ::showCardConfirming,
                )
                val chargedAmount = CardAmountResolver.CardAmount(
                    minorUnits = paymentIntent.amount,
                    currency = paymentIntent.currency ?: amount.currency,
                )
                onCardPaymentCharged(paymentIntent.id.orEmpty(), chargedAmount)
            } catch (e: CancellationException) {
                throw e
            } catch (e: StripeTerminalManager.CardPaymentException) {
                Log.w(TAG, "Card payment did not complete: ${e.message}", e)
                val currency = amount.currency.uppercase(java.util.Locale.US)
                when (e.reason) {
                    // Setup problems: retrying would fail the same way, so offer no retry.
                    StripeTerminalManager.FailureReason.AMOUNT_TOO_SMALL -> showCardIdle(
                        getString(R.string.card_payment_status_amount_too_small, formatCardAmount(amount)),
                        showRetry = false,
                    )
                    StripeTerminalManager.FailureReason.CURRENCY_NOT_SUPPORTED -> showCardIdle(
                        getString(R.string.card_payment_status_currency_not_supported, currency),
                        showRetry = false,
                    )
                    StripeTerminalManager.FailureReason.SIMULATION_REQUIRES_TEST_KEY -> showCardIdle(
                        getString(R.string.card_payment_simulation_requires_test_key),
                        showRetry = false,
                    )
                    StripeTerminalManager.FailureReason.OTHER -> showCardIdle(
                        when {
                            e.isCancellation -> getString(R.string.card_payment_status_cancelled)
                            e.isUnresolved -> getString(R.string.card_payment_status_unresolved)
                            else -> getString(R.string.card_payment_status_failed, e.message.orEmpty())
                        },
                        showRetry = true,
                        needsStatusCheck = e.isUnresolved,
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Card reader error", e)
                if (e is TerminalException && e.errorCode == TerminalErrorCode.TAP_TO_PAY_DEBUG_NOT_SUPPORTED) {
                    showCardIdle(getString(R.string.card_payment_debug_reader_unsupported), showRetry = false)
                } else {
                    val reason = (e as? TerminalException)?.errorMessage ?: e.message ?: e.javaClass.simpleName
                    showCardIdle(getString(R.string.card_payment_status_failed, reason), showRetry = true)
                }
            }
        }
    }

    /**
     * The card attempt is running: stay on checkout with a clear button state while Stripe
     * connects the reader and prepares the PaymentIntent, then park a neutral cover underneath
     * Stripe once its Tap to Pay Activity takes foreground.
     */
    private fun showCardBusy() {
        isCardCollecting = true
        isCardTapScreenLaunched = false
        NdefHostCardEmulationService.getInstance()?.clearPaymentRequest()
        cardMessageText.text = ""
        cardPayButton.isEnabled = false
        cardPayButton.alpha = 1f
        cardPayButton.text = getString(R.string.card_payment_button_preparing)
        cardPayButton.icon = IndeterminateDrawable.createCircularDrawable(
            cardPayButton.context,
            CircularProgressIndicatorSpec(
                cardPayButton.context,
                null,
                0,
                com.google.android.material.R.style.Widget_Material3_CircularProgressIndicator_ExtraSmall,
            ),
        )
    }

    /** Marks that Stripe's Tap to Pay Activity is launching over checkout. */
    private fun onCardCollectionStarting() {
        if (hasTerminalOutcome) return
        isCardTapScreenLaunched = true
    }

    /**
     * Called from [onPause]: when Stripe's Tap to Pay Activity takes foreground over checkout,
     * parks a neutral tap surface beneath it so dismissing Stripe never flashes the QR screen.
     */
    private fun maybeParkCardSurfaceCoverOnPause() {
        if (isCardTapScreenLaunched && isCardCollecting && !isCardConfirming && !hasTerminalOutcome) {
            showCardSurfaceCover()
        }
    }

    /**
     * Covers checkout with the matching neutral tap surface while Stripe's Tap to Pay Activity
     * sits in front, so dismissing Stripe never flashes the QR screen.
     */
    private fun showCardSurfaceCover() {
        if (hasTerminalOutcome || isCardConfirming) return
        isCardSurfaceCovered = true
        applyFullscreenForAnimationOverlay(onResultColor = false)
        revealOverlay(animated = false)
        resetResultTextViews()
        resetResultActionButtons()
        nfcAnimationView.showSurfaceCover()
    }

    /**
     * Stripe's tap screen has read the card and is closing while the charge is confirmed. Numo's
     * matching confirming screen enters already filled with its orbiting rim, with zero pulse flash.
     */
    private fun showCardConfirming() {
        if (hasTerminalOutcome) return
        isCardConfirming = true
        isCardSurfaceCovered = false

        applyFullscreenForAnimationOverlay(onResultColor = false)
        hideStatusBarWhileWaiting()
        revealOverlay(animated = false)
        resetResultTextViews()
        resetResultActionButtons()
        // The card tap screen drops its total once it has read the card, so it stays hidden.
        showNfcHint(getString(R.string.card_payment_status_confirming), animated = true)

        nfcAnimationView.startProcessingDirectly(animateIn = true)
        ViewCompat.requestApplyInsets(nfcAnimationContainer)
    }

    /**
     * The card attempt ended without a charge: bitcoin is on offer again and [message] says
     * what happened. [showRetry] is false for problems another tap cannot fix.
     */
    private fun showCardIdle(message: String, showRetry: Boolean, needsStatusCheck: Boolean = false) {
        isCardTapScreenLaunched = false
        if (isCardConfirming || isCardSurfaceCovered) {
            isCardConfirming = false
            isCardSurfaceCovered = false
            if (!hasTerminalOutcome) hideNfcAnimationOverlay()
        }
        if (isCardCollecting) {
            isCardCollecting = false
            if (!hasTerminalOutcome) publishHcePaymentRequest()
        }
        cardNeedsStatusCheck = needsStatusCheck
        cardMessageText.text = message
        cardPayButton.setIconResource(R.drawable.ic_card)
        cardPayButton.isEnabled = showRetry && !hasTerminalOutcome
        cardPayButton.alpha = if (cardPayButton.isEnabled) 1f else DISABLED_ALPHA
        updateCardButtonLabel()
    }

    /**
     * Handles a card Stripe has charged. Normally this completes checkout. If checkout was
     * already closed or paid another way, the charge is still recorded or reported.
     */
    private fun onCardPaymentCharged(paymentIntentId: String) {
        onCardPaymentCharged(paymentIntentId, cardAmount)
    }

    private fun onCardPaymentCharged(
        paymentIntentId: String,
        chargedAmount: CardAmountResolver.CardAmount?,
    ) {
        if (hasTerminalOutcome || isDestroyed) {
            recordLateCardPayment(paymentIntentId, chargedAmount)
        } else {
            handleCardPaymentSuccess(paymentIntentId, chargedAmount)
        }
    }

    /**
     * Records a card charge that completed after this checkout ended. If the sale is still
     * pending it is completed as a card payment; if it was already paid, the merchant is told
     * to refund the extra charge.
     */
    private fun recordLateCardPayment(
        paymentIntentId: String,
        chargedAmount: CardAmountResolver.CardAmount?,
    ) {
        val context = applicationContext
        val paymentId = pendingPaymentId
        val stillPending = paymentId != null &&
            PaymentsHistoryActivity.getPaymentEntryById(context, paymentId)?.isPending() == true
        if (paymentId != null && stillPending) {
            Log.w(TAG, "Recording card payment $paymentIntentId that completed after checkout closed")
            PaymentsHistoryActivity.completePendingPayment(
                context = context,
                paymentId = paymentId,
                token = "",
                paymentType = PaymentHistoryEntry.TYPE_CARD,
                mintUrl = null,
                paymentRequest = paymentIntentId,
                cardAmountMinorUnits = chargedAmount?.minorUnits,
                cardCurrency = chargedAmount?.currency,
            )
            dispatchPaymentReceivedWebhook()
            markBasketAsPaid()
            Toast.makeText(context, R.string.card_payment_completed_late, Toast.LENGTH_LONG).show()
        } else {
            Log.e(TAG, "Card payment $paymentIntentId succeeded after this sale was already settled")
            Toast.makeText(context, R.string.card_payment_also_charged, Toast.LENGTH_LONG).show()
        }
    }

    /** Records a captured card payment. Stripe holds the funds; nothing enters the Cashu wallet. */
    private fun handleCardPaymentSuccess(paymentIntentId: String) {
        handleCardPaymentSuccess(paymentIntentId, cardAmount)
    }

    private fun handleCardPaymentSuccess(
        paymentIntentId: String,
        chargedAmount: CardAmountResolver.CardAmount?,
    ) {
        if (!beginTerminalOutcome("card_success")) return

        Log.d(TAG, "Card payment succeeded: $paymentIntentId")
        cancelNfcSafetyTimeout()

        statusText.visibility = View.VISIBLE
        statusText.text = getString(R.string.payment_request_status_success)

        pendingPaymentId?.let { paymentId ->
            PaymentsHistoryActivity.completePendingPayment(
                context = this,
                paymentId = paymentId,
                token = "",
                paymentType = PaymentHistoryEntry.TYPE_CARD,
                mintUrl = null,
                paymentRequest = paymentIntentId,
                cardAmountMinorUnits = chargedAmount?.minorUnits,
                cardCurrency = chargedAmount?.currency,
            )
        }

        dispatchPaymentReceivedWebhook()

        val resultIntent = Intent().apply {
            putExtra(RESULT_EXTRA_TOKEN, "")
            putExtra(RESULT_EXTRA_AMOUNT, paymentAmount)
        }
        setResult(Activity.RESULT_OK, resultIntent)

        // Stripe's Tap to Pay screen already plays its approval tone, so skip ours.
        showPaymentSuccess("", paymentAmount, playSound = false)
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
        cancelCardCollection()

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

        // Render the native failure overlay; enter directly when no tap screen was active so
        // non-NFC failures never flash the contactless waiting disc.
        val fromActiveTapOverlay = nfcAnimationContainer.visibility == View.VISIBLE && !isCardSurfaceCovered
        isCardSurfaceCovered = false
        if (!fromActiveTapOverlay) {
            nfcOverlayShownAtMs = SystemClock.elapsedRealtime()
            applyFullscreenForAnimationOverlay(onResultColor = true)
            revealOverlay(animated = false)
            nfcAnimationView.reset()
            resetResultTextViews()
            resetResultActionButtons()
            ViewCompat.requestApplyInsets(nfcAnimationContainer)
        } else {
            applyFullscreenForAnimationOverlay(onResultColor = true)
        }

        showNfcAnimationError(errorMessage, direct = !fromActiveTapOverlay)
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

        clearPendingHceReadyListener()

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

        cancelCardCollection()

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
        cancelCardCollection()
        clearPendingHceReadyListener()
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
    private fun showPaymentSuccess(
        token: String,
        amount: Long,
        receivedMintUrl: String? = null,
        playSound: Boolean = true,
    ) {
        val fromActiveTapOverlay = nfcAnimationContainer.visibility == View.VISIBLE && !isCardSurfaceCovered
        isCardSurfaceCovered = false
        if (!fromActiveTapOverlay) {
            nfcOverlayShownAtMs = SystemClock.elapsedRealtime()
            Log.d(TAG, "overlay_shown_ms=$nfcOverlayShownAtMs")
            applyFullscreenForAnimationOverlay(onResultColor = true)
            revealOverlay(animated = false)
            nfcAnimationView.reset()
            resetResultTextViews()
            resetResultActionButtons()
            ViewCompat.requestApplyInsets(nfcAnimationContainer)
        } else {
            applyFullscreenForAnimationOverlay(onResultColor = true)
        }
        pendingNfcSuccessToken = token
        pendingNfcSuccessMintUrl = receivedMintUrl
        pendingNfcSuccessAmount = amount
        showNfcAnimationSuccess(formattedAmountString, playSound, direct = !fromActiveTapOverlay)
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
        nfcAnimationView.setOnGeometryChangedListener {
            positionOverlayTextBelowDisc()
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
        clearPendingHceReadyListener()
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

        cancelCardCollection()

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

        isCardSurfaceCovered = false
        applyFullscreenForAnimationOverlay(onResultColor = false)
        // Hiding the status bar does not shift checkout (root uses getInsetsIgnoringVisibility),
        // and still waits until the waiting screen has faded in so the chrome feels steady.
        revealOverlay(animated = true) {
            if (nfcAnimationContainer.visibility == View.VISIBLE && !hasTerminalOutcome) {
                hideStatusBarWhileWaiting()
            }
        }
        resetResultTextViews()
        resetResultActionButtons()
        pendingNfcSuccessToken = null
        pendingNfcSuccessMintUrl = null
        pendingNfcSuccessAmount = 0

        // Show "Keep phone close" hint during NFC communication, top-anchored beneath the disc
        showNfcHint(getString(R.string.nfc_payment_hint_keep_close))
        showWaitingTotal()
        
        nfcAnimationView.reset()
        nfcAnimationView.startReading(animateIn = true)
        nfcAnimationStartedAtMs = SystemClock.elapsedRealtime()
        Log.d(TAG, "animation_started_ms=${nfcAnimationStartedAtMs - nfcOverlayShownAtMs}")
        startNfcSafetyTimeout()
        ViewCompat.requestApplyInsets(nfcAnimationContainer)
    }

    private fun showNfcAnimationProcessing() {
        // Vibrate when switching to processing phase
        try {
            val vibrator = getVibrator()
            vibrator?.vibrateCompat(longArrayOf(0, 50), -1)
        } catch (_: Exception) {}

        val alreadyShowingTap = nfcAnimationContainer.visibility == View.VISIBLE && !isCardSurfaceCovered
        isCardSurfaceCovered = false

        if (!alreadyShowingTap) {
            // Token arrived without an active waiting screen (e.g. after a long biometric pause):
            // cover checkout immediately so the user never sees the QR code during redemption.
            nfcOverlayShownAtMs = SystemClock.elapsedRealtime()
            applyFullscreenForAnimationOverlay(onResultColor = false)
            revealOverlay(animated = true) {
                if (nfcAnimationContainer.visibility == View.VISIBLE && !hasTerminalOutcome) {
                    hideStatusBarWhileWaiting()
                }
            }
            resetResultTextViews()
            resetResultActionButtons()
            showNfcHint(getString(R.string.nfc_payment_hint_processing), animated = true)
            nfcAnimationView.startProcessingDirectly(animateIn = true)
            ViewCompat.requestApplyInsets(nfcAnimationContainer)
            return
        }

        keepOverlayShown()
        swapWaitingHint(getString(R.string.nfc_payment_hint_processing))
        // The card tap screen drops its total once it has read the card.
        hideWaitingTotal(animated = true)
        nfcAnimationView.startProcessing()
    }

    /**
     * Gentle, recoverable feedback when the customer lifts their phone mid-write: keeps the
     * invoice, QR, and HCE live so an immediate second tap works without restarting checkout.
     */
    private fun showRecoverableTapLost() {
        if (hasTerminalOutcome || isProcessingNfcPayment) return
        try {
            val vibrator = getVibrator()
            vibrator?.vibrateCompat(longArrayOf(0, 40, 60, 40), -1)
        } catch (_: Exception) {}

        val hintText = getString(R.string.nfc_payment_hint_tap_lost)
        if (nfcAnimationContainer.visibility != View.VISIBLE || isCardSurfaceCovered) {
            showNfcAnimationOverlay()
        } else {
            keepOverlayShown()
        }
        swapWaitingHint(hintText, shakeAfter = true)
        startNfcSafetyTimeout()
    }

    /** Shows [text] in the dedicated waiting hint top-anchored beneath the disc. */
    private fun showNfcHint(text: String, animated: Boolean = false) {
        val hint = animationWaitingHintText ?: return
        hint.animate().cancel()
        hint.translationX = 0f
        hint.typeface = interFont(R.font.inter_semi_bold)
        hint.setTextColor(ContextCompat.getColor(this, R.color.color_text_primary))
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, NFC_HINT_TEXT_SP)
        hint.text = text
        hint.visibility = View.VISIBLE
        positionOverlayTextBelowDisc()
        if (animated && isAnimationEnabled()) {
            hint.alpha = 0f
            hint.animate()
                .alpha(NFC_HINT_ALPHA)
                .setDuration(NFC_HINT_SWAP_IN_MS)
                .setInterpolator(resultEnterInterpolator)
                .start()
        } else {
            hint.alpha = NFC_HINT_ALPHA
        }
    }

    /** Crossfades the waiting hint in place — top-anchored, so 1-line -> 2-line never jumps. */
    private fun swapWaitingHint(text: String, shakeAfter: Boolean = false) {
        val hint = animationWaitingHintText ?: return
        hint.animate().cancel()
        hint.translationX = 0f
        if (!isAnimationEnabled() || hint.visibility != View.VISIBLE) {
            showNfcHint(text, animated = false)
            return
        }
        if (hint.text.toString() == text && hint.alpha >= 0.99f) {
            if (shakeAfter) hint.shake(amplitude = 10f, duration = 320L)
            return
        }
        hint.animate()
            .alpha(0f)
            .setDuration(NFC_HINT_SWAP_OUT_MS)
            .setInterpolator(resultEnterInterpolator)
            .withEndAction {
                hint.text = text
                positionOverlayTextBelowDisc()
                hint.animate()
                    .alpha(NFC_HINT_ALPHA)
                    .setDuration(NFC_HINT_SWAP_IN_MS)
                    .setInterpolator(resultEnterInterpolator)
                    .withEndAction {
                        if (shakeAfter) hint.shake(amplitude = 10f, duration = 320L)
                    }
                    .start()
            }
            .start()
    }

    /** Smoothly fades out the waiting hint when the result takes over, instead of blinking out. */
    private fun hideWaitingHint(animated: Boolean) {
        val hint = animationWaitingHintText ?: return
        hint.animate().cancel()
        if (!animated || hint.visibility != View.VISIBLE || !isAnimationEnabled()) {
            hint.visibility = View.GONE
            hint.alpha = 0f
            hint.text = ""
            return
        }
        hint.animate()
            .alpha(0f)
            .setDuration(NfcPaymentAnimationView.WAIT_FADE_OUT_MS)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                hint.visibility = View.GONE
                hint.text = ""
            }
            .start()
    }

    /**
     * Anchors the top edges of the waiting hint and result text stack below the animation's disc.
     * Because both views use Gravity.TOP inside the overlay, multi-line text grows downward
     * cleanly without any manual measure() or translationY jumps.
     */
    private fun positionOverlayTextBelowDisc() {
        val containerHeight = nfcAnimationContainer.height
        val discBottom = nfcAnimationView.discBottom
        if (containerHeight <= 0 || discBottom <= 0f) {
            nfcAnimationContainer.doOnLayout { positionOverlayTextBelowDisc() }
            return
        }

        val hintTop = (discBottom + dpToPx(NFC_HINT_GAP_DP)).toInt()
        setTopMargin(animationWaitingHintText, hintTop)

        val preferredResultTop = (discBottom + dpToPx(NFC_RESULT_GAP_DP)).toInt()
        val maxResultTop = (containerHeight - dpToPx(RESULT_ACTIONS_RESERVE_DP) - dpToPx(RESULT_STACK_MIN_HEIGHT_DP))
            .coerceAtLeast(hintTop)
        setTopMargin(animationResultTextContainer, minOf(preferredResultTop, maxResultTop))
    }

    /** Steps the amount down from its full size until it fits on one line. */
    private fun fitResultAmountToWidth() {
        val containerWidth = nfcAnimationContainer.width
        if (containerWidth <= 0) return
        val parentMargins = animationResultTextContainer?.layoutParams as? MarginLayoutParams
        val margins = animationResultAmountText.layoutParams as? MarginLayoutParams
        val available = containerWidth -
            (parentMargins?.marginStart ?: 0) - (parentMargins?.marginEnd ?: 0) -
            (margins?.marginStart ?: 0) - (margins?.marginEnd ?: 0)
        if (available <= 0) return
        var sizeSp = RESULT_AMOUNT_MAX_TEXT_SP
        animationResultAmountText.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        val amount = animationResultAmountText.text.toString()
        while (sizeSp > RESULT_AMOUNT_MIN_TEXT_SP &&
            animationResultAmountText.paint.measureText(amount) > available
        ) {
            sizeSp -= RESULT_AMOUNT_TEXT_STEP_SP
            animationResultAmountText.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        }
    }

    private fun hideNfcAnimationOverlay() {
        cancelNfcSafetyTimeout()
        isProcessingNfcPayment = false
        isCardSurfaceCovered = false

        nfcAnimationContainer.animate().cancel()
        if (nfcAnimationContainer.visibility != View.VISIBLE || !isAnimationEnabled()) {
            clearHiddenOverlay()
            return
        }
        // Checkout returns as the waiting screen fades away over it; system bars restore cleanly
        // at the end (and checkout stays rock-still thanks to getInsetsIgnoringVisibility).
        nfcAnimationContainer.animate()
            .alpha(0f)
            .setDuration(OVERLAY_FADE_OUT_MS)
            .setInterpolator(resultEnterInterpolator)
            .withEndAction { clearHiddenOverlay() }
            .start()
    }

    /** Stops a fade-out in progress, for when the overlay turns out to be needed after all. */
    private fun keepOverlayShown() {
        nfcAnimationContainer.animate().cancel()
        nfcAnimationContainer.alpha = 1f
    }

    private fun clearHiddenOverlay() {
        nfcAnimationContainer.visibility = View.GONE
        nfcAnimationContainer.alpha = 1f
        nfcAnimationView.reset()
        resetResultTextViews()
        resetResultActionButtons()
        restoreSystemBarsAfterAnimation()
    }

    /**
     * Shows the overlay over checkout. A tap fades it in; a result or the return from the card
     * tap screen must cover checkout at once.
     */
    private fun revealOverlay(animated: Boolean, onShown: () -> Unit = {}) {
        nfcAnimationContainer.animate().cancel()
        nfcAnimationContainer.visibility = View.VISIBLE
        if (!animated || !isAnimationEnabled()) {
            nfcAnimationContainer.alpha = 1f
            onShown()
            return
        }
        nfcAnimationContainer.alpha = 0f
        nfcAnimationContainer.animate()
            .alpha(1f)
            .setDuration(OVERLAY_FADE_IN_MS)
            .setInterpolator(resultEnterInterpolator)
            .withEndAction(onShown)
            .start()
    }

    private fun startNfcSafetyTimeout() {
        cancelNfcSafetyTimeout()

        nfcAnimationTimeoutRunnable = Runnable {
            if (hasTerminalOutcome || isProcessingNfcPayment || nfcAnimationContainer.visibility != View.VISIBLE) {
                return@Runnable
            }
            // Non-terminal timeout: gracefully fade the waiting screen back to the live QR
            // rather than destroying an unpaid checkout.
            Log.w(TAG, "NFC waiting timeout elapsed without token - returning to checkout")
            hideNfcAnimationOverlay()
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

    @JvmOverloads
    private fun showNfcAnimationSuccess(
        amountText: String,
        playSound: Boolean = true,
        direct: Boolean = false,
    ) {
        if (nfcAnimationContainer.visibility != View.VISIBLE) return
        keepOverlayShown()

        currentOverlayActionMode = OverlayActionMode.SUCCESS
        animationResultAmountText.typeface = interFont(R.font.inter_semi_bold)
        animationResultLabelText.typeface = interFont(R.font.inter_medium)
        animationResultLabelText.letterSpacing = 0f
        animationResultLabelText.setTextColor(android.graphics.Color.WHITE)
        animationResultLabelText.setTextSize(TypedValue.COMPLEX_UNIT_SP, RESULT_CAPTION_TEXT_SP)
        animationResultLabelText.maxLines = 1
        animationResultAmountText.text = amountText
        animationResultLabelText.text = getString(R.string.transaction_detail_type_payment_received)

        if (direct) {
            nfcAnimationView.showSuccessDirect(amountText)
        } else {
            nfcAnimationView.showSuccess(amountText)
        }
        hideWaitingHint(animated = !direct)
        hideWaitingTotal(animated = !direct)
        showResultTextWithTransition(showAmount = true, direct = direct)

        // Play success sound and vibration
        playNfcSuccessFeedback(playSound)
    }

    private fun playNfcSuccessFeedback(playSound: Boolean) {
        if (playSound) {
            try {
                val mediaPlayer = android.media.MediaPlayer.create(this, R.raw.success_sound)
                mediaPlayer?.setOnCompletionListener { it.release() }
                mediaPlayer?.start()
            } catch (_: Exception) {}
        }
        
        // Vibrate
        val vibrator = getVibrator()
        vibrator?.vibrateCompat(longArrayOf(0, 50, 100, 50), -1)
    }

    @JvmOverloads
    private fun showNfcAnimationError(message: String, direct: Boolean = false) {
        if (nfcAnimationContainer.visibility != View.VISIBLE) return
        keepOverlayShown()

        currentOverlayActionMode = OverlayActionMode.ERROR
        animationResultLabelText.typeface = interFont(R.font.inter_semi_bold)
        animationResultLabelText.letterSpacing = -0.02f
        animationResultLabelText.setTextColor(android.graphics.Color.WHITE)
        animationResultLabelText.setTextSize(TypedValue.COMPLEX_UNIT_SP, RESULT_HEADLINE_TEXT_SP)
        animationResultLabelText.maxLines = 2
        animationResultAmountText.text = ""
        animationResultLabelText.text = getString(R.string.payment_failure_title)

        if (direct) {
            nfcAnimationView.showErrorDirect(message)
        } else {
            nfcAnimationView.showError(message)
        }
        hideWaitingHint(animated = !direct)
        hideWaitingTotal(animated = !direct)
        showResultTextWithTransition(showAmount = false, direct = direct)
    }

    private fun onNfcAnimationResultDisplayed(success: Boolean) {
        val elapsedMs = if (nfcOverlayShownAtMs > 0) {
            SystemClock.elapsedRealtime() - nfcOverlayShownAtMs
        } else {
            0L
        }
        Log.d(TAG, "success_rendered_ms=$elapsedMs")

        val mode = if (success) OverlayActionMode.SUCCESS else OverlayActionMode.ERROR
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
        applyFullscreenForAnimationOverlay(onResultColor = true)
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

        if (!isAnimationEnabled()) {
            animationActionsContainer.alpha = 1f
            animationActionsContainer.translationY = 0f
            animationActionsContainer.visibility = View.VISIBLE
            return
        }

        // Start from invisible and below
        animationActionsContainer.alpha = 0f
        animationActionsContainer.translationY = dpToPx(RESULT_ACTIONS_TRAVEL_DP).toFloat()
        animationActionsContainer.visibility = View.VISIBLE

        // Animate in with fade + slide up
        animationActionsContainer.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(RESULT_ACTIONS_DURATION_MS)
            .setInterpolator(resultEnterInterpolator)
            .start()
    }

    /**
     * Places the result stack under the circle and brings it in alongside the result transition.
     */
    private fun showResultTextWithTransition(showAmount: Boolean, direct: Boolean = false) {
        nfcAnimationContainer.doOnLayout {
            positionOverlayTextBelowDisc()
            if (showAmount) fitResultAmountToWidth()
            animateResultTextIn(showAmount, direct)
        }
    }

    /** The text rises into place as the check is drawn; the actions follow once it has landed. */
    private fun animateResultTextIn(showAmount: Boolean, direct: Boolean = false) {
        animationResultAmountText.visibility = if (showAmount) View.VISIBLE else View.GONE
        animationResultLabelText.visibility = View.VISIBLE

        val container = animationResultTextContainer
        val travel = dpToPx(RESULT_TEXT_TRAVEL_DP).toFloat()
        val delayMs = if (direct) DIRECT_RESULT_TEXT_DELAY_MS else RESULT_TEXT_DELAY_MS

        resultTextAnimator?.cancel()
        if (container != null) {
            animationResultAmountText.alpha = 1f
            animationResultLabelText.alpha = 1f
            container.visibility = View.VISIBLE
            if (!isAnimationEnabled()) {
                container.alpha = 1f
                container.translationY = 0f
                return
            }
            container.alpha = 0f
            container.translationY = travel
            resultTextAnimator = AnimatorSet().apply {
                startDelay = delayMs
                duration = RESULT_TEXT_DURATION_MS
                interpolator = resultEnterInterpolator
                playTogether(
                    ObjectAnimator.ofFloat(container, View.ALPHA, 0f, 1f),
                    ObjectAnimator.ofFloat(container, View.TRANSLATION_Y, travel, 0f),
                )
                start()
            }
            return
        }

        // Fallback when views are injected directly without inflating the XML container in unit tests.
        if (!isAnimationEnabled()) {
            animationResultAmountText.alpha = 1f
            animationResultLabelText.alpha = 1f
            return
        }
        if (showAmount) {
            animationResultAmountText.alpha = 0f
            animationResultAmountText.translationY = travel
        }
        animationResultLabelText.alpha = 0f
        animationResultLabelText.translationY = travel
        val animators = mutableListOf<android.animation.Animator>().apply {
            if (showAmount) {
                add(ObjectAnimator.ofFloat(animationResultAmountText, View.ALPHA, 0f, 1f))
                add(ObjectAnimator.ofFloat(animationResultAmountText, View.TRANSLATION_Y, travel, 0f))
            }
            add(ObjectAnimator.ofFloat(animationResultLabelText, View.ALPHA, 0f, 1f))
            add(ObjectAnimator.ofFloat(animationResultLabelText, View.TRANSLATION_Y, travel, 0f))
        }
        resultTextAnimator = AnimatorSet().apply {
            startDelay = delayMs
            duration = RESULT_TEXT_DURATION_MS
            interpolator = resultEnterInterpolator
            playTogether(animators)
            start()
        }
    }

    /** Shows the amount at the foot of the waiting screen, as the card tap screen does. */
    private fun showWaitingTotal() {
        val container = animationTotalContainer ?: return
        animationTotalAmountText?.text = formattedAmountString
        container.animate().cancel()
        if (!isAnimationEnabled()) {
            container.alpha = 1f
            container.visibility = View.VISIBLE
            return
        }
        container.alpha = 0f
        container.visibility = View.VISIBLE
        container.animate()
            .alpha(1f)
            .setDuration(WAITING_TOTAL_FADE_IN_MS)
            .setInterpolator(resultEnterInterpolator)
            .start()
    }

    /** The result shows the amount under the disc, and the actions take the total's place. */
    private fun hideWaitingTotal(animated: Boolean) {
        val container = animationTotalContainer ?: return
        container.animate().cancel()
        if (!animated || container.visibility != View.VISIBLE || !isAnimationEnabled()) {
            container.visibility = View.GONE
            return
        }
        container.animate()
            .alpha(0f)
            .setDuration(WAITING_TOTAL_FADE_OUT_MS)
            .withEndAction { container.visibility = View.GONE }
            .start()
    }

    private fun resetResultTextViews() {
        hideWaitingHint(animated = false)
        hideWaitingTotal(animated = false)
        resultTextAnimator?.cancel()
        resultTextAnimator = null
        animationResultTextContainer?.apply {
            animate().cancel()
            visibility = View.GONE
            alpha = 0f
            translationY = 0f
        }
        animationResultAmountText.animate().cancel()
        animationResultAmountText.visibility = View.GONE
        animationResultAmountText.alpha = 0f
        animationResultAmountText.translationY = 0f
        animationResultAmountText.text = ""

        animationResultLabelText.animate().cancel()
        animationResultLabelText.visibility = View.GONE
        animationResultLabelText.alpha = 0f
        animationResultLabelText.translationY = 0f
        animationResultLabelText.setTextSize(TypedValue.COMPLEX_UNIT_SP, RESULT_CAPTION_TEXT_SP)
        animationResultLabelText.letterSpacing = 0f
        animationResultLabelText.maxLines = 3
        animationResultLabelText.text = ""
    }

    private fun interFont(resId: Int): android.graphics.Typeface = try {
        ResourcesCompat.getFont(this, resId)
            ?: android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    } catch (_: Exception) {
        android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
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

    private fun isDarkTheme(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun applyFullscreenForAnimationOverlay(onResultColor: Boolean = false) {
        isStatusBarHiddenForWaiting = false
        currentOverlayBarStyle = if (onResultColor) OverlayBarStyle.RESULT_COLOR else OverlayBarStyle.TAP_SURFACE
        val useDarkBarIcons = !onResultColor && !isDarkTheme()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            show(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            isAppearanceLightStatusBars = useDarkBarIcons
            isAppearanceLightNavigationBars = useDarkBarIcons
        }
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
    }

    /**
     * The card tap screen has no status bar, so the waiting screen has none either. The result
     * and the return to checkout both show the bars again.
     */
    private fun hideStatusBarWhileWaiting() {
        isStatusBarHiddenForWaiting = true
        WindowInsetsControllerCompat(window, window.decorView)
            .hide(WindowInsetsCompat.Type.statusBars())
    }

    private fun restoreSystemBarsAfterAnimation() {
        isStatusBarHiddenForWaiting = false
        val useDarkBarIcons = !isDarkTheme()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            show(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
            isAppearanceLightStatusBars = useDarkBarIcons
            isAppearanceLightNavigationBars = useDarkBarIcons
        }
        findViewById<View?>(R.id.payment_request_root)?.let { ViewCompat.requestApplyInsets(it) }
    }

    private fun adjustAnimationActionsBottomMargin(insets: WindowInsetsCompat) {
        val systemInsets = insets.getInsetsIgnoringVisibility(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        setBottomMargin(
            animationActionsContainer,
            systemInsets.bottom + dpToPx(RESULT_ACTIONS_BOTTOM_GAP_DP),
        )
        // The card tap screen keeps its total a fixed gap from the screen's edge, over the
        // gesture bar. A taller navigation bar would cover the amount, so it sits above that.
        val totalGap = dpToPx(WAITING_TOTAL_BOTTOM_GAP_DP)
        val gestureBarMax = dpToPx(GESTURE_BAR_MAX_HEIGHT_DP)
        setBottomMargin(
            animationTotalContainer,
            if (systemInsets.bottom <= gestureBarMax) totalGap else systemInsets.bottom,
        )
    }

    private fun setTopMargin(view: View?, topMargin: Int) {
        val layoutParams = view?.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (layoutParams.topMargin != topMargin) {
            layoutParams.topMargin = topMargin
            view.layoutParams = layoutParams
        }
    }

    private fun setBottomMargin(view: View?, bottomMargin: Int) {
        val layoutParams = view?.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (layoutParams.bottomMargin != bottomMargin) {
            layoutParams.bottomMargin = bottomMargin
            view.layoutParams = layoutParams
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
            ),
        )
    }

    private fun dpToPx(dp: Float): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    companion object {
        private const val TAG = "PaymentRequestActivity"
        private const val DISABLED_ALPHA = 0.38f
        private const val NFC_HINT_ALPHA = 1f
        private const val NFC_HINT_TEXT_SP = 18f
        private const val NFC_HINT_GAP_DP = 24f
        private const val NFC_HINT_SWAP_OUT_MS = 90L
        private const val NFC_HINT_SWAP_IN_MS = 160L
        private const val OVERLAY_FADE_IN_MS = 180L
        private const val OVERLAY_FADE_OUT_MS = 120L
        private const val QR_REVEAL_MS = 180L
        private const val QR_SPINNER_FADE_MS = 120L
        private const val NFC_RESULT_GAP_DP = 28f
        private const val RESULT_AMOUNT_MAX_TEXT_SP = 56f
        private const val RESULT_AMOUNT_MIN_TEXT_SP = 36f
        private const val RESULT_AMOUNT_TEXT_STEP_SP = 2f
        private const val RESULT_CAPTION_TEXT_SP = 18f
        private const val RESULT_HEADLINE_TEXT_SP = 28f
        private const val RESULT_TEXT_TRAVEL_DP = 12f
        private const val RESULT_TEXT_DELAY_MS = 180L
        private const val DIRECT_RESULT_TEXT_DELAY_MS = 140L
        private const val RESULT_TEXT_DURATION_MS = 260L
        private const val RESULT_ACTIONS_TRAVEL_DP = 16f
        private const val RESULT_ACTIONS_DURATION_MS = 300L
        private const val RESULT_ACTIONS_BOTTOM_GAP_DP = 24f
        private const val RESULT_ACTIONS_RESERVE_DP = 168f
        private const val RESULT_STACK_MIN_HEIGHT_DP = 104f
        private const val WAITING_TOTAL_BOTTOM_GAP_DP = 16f
        private const val GESTURE_BAR_MAX_HEIGHT_DP = 24f
        private const val WAITING_TOTAL_FADE_IN_MS = 200L
        private const val WAITING_TOTAL_FADE_OUT_MS = 150L
        private const val NFC_READ_TIMEOUT_MS = 12_000L
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
