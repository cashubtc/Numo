package com.electricdreams.shellshock

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.electricdreams.shellshock.core.model.Amount
import com.electricdreams.shellshock.core.model.Amount.Currency
import com.electricdreams.shellshock.core.util.MintManager
import com.electricdreams.shellshock.core.worker.BitcoinPriceWorker
import com.electricdreams.shellshock.core.util.CurrencyManager
import com.electricdreams.shellshock.ndef.CashuPaymentHelper
import com.electricdreams.shellshock.ndef.NdefHostCardEmulationService
import com.electricdreams.shellshock.nostr.NostrKeyPair
import com.electricdreams.shellshock.nostr.NostrPaymentListener
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

class PaymentRequestActivity : AppCompatActivity() {

    private lateinit var qrImageView: ImageView
    private lateinit var largeAmountDisplay: TextView
    private lateinit var convertedAmountDisplay: TextView
    private lateinit var statusText: TextView
    private lateinit var closeButton: View
    private lateinit var shareButton: View
    private lateinit var nfcAnimationContainer: View
    private lateinit var nfcAnimationWebView: WebView
    private lateinit var animationCloseButton: ImageButton

    private var paymentAmount: Long = 0
    private var bitcoinPriceWorker: BitcoinPriceWorker? = null
    private var hcePaymentRequest: String? = null
    private var qrPaymentRequest: String? = null
    private var nostrListener: NostrPaymentListener? = null
    private var webViewReady = false
    private var pendingToken: String? = null
    private var formattedAmount: String = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_payment_request)

        // Initialize views
        qrImageView = findViewById(R.id.payment_request_qr)
        largeAmountDisplay = findViewById(R.id.large_amount_display)
        convertedAmountDisplay = findViewById(R.id.converted_amount_display)
        statusText = findViewById(R.id.payment_status_text)
        closeButton = findViewById(R.id.close_button)
        shareButton = findViewById(R.id.share_button)
        nfcAnimationContainer = findViewById(R.id.nfc_animation_container)
        nfcAnimationWebView = findViewById(R.id.nfc_animation_webview)
        animationCloseButton = findViewById(R.id.animation_close_button)

        // Setup WebView for NFC animation
        setupAnimationWebView()

        // Initialize Bitcoin price worker
        bitcoinPriceWorker = BitcoinPriceWorker.getInstance(this)

        // Get payment amount from intent
        paymentAmount = intent.getLongExtra(EXTRA_PAYMENT_AMOUNT, 0)

        if (paymentAmount <= 0) {
            Log.e(TAG, "Invalid payment amount: $paymentAmount")
            Toast.makeText(this, "Invalid payment amount", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Get formatted amount string if provided, otherwise format as BTC
        formattedAmount = intent.getStringExtra(EXTRA_FORMATTED_AMOUNT)
            ?: Amount(paymentAmount, Currency.BTC).toString()
        
        // Display amount (without "Pay" prefix since it's in the label above)
        largeAmountDisplay.text = formattedAmount

        // Calculate and display converted amount
        updateConvertedAmount(formattedAmount)

        // Set up buttons
        closeButton.setOnClickListener {
            Log.d(TAG, "Payment cancelled by user")
            cancelPayment()
        }

        shareButton.setOnClickListener {
            qrPaymentRequest?.let { paymentRequest ->
                sharePaymentRequest(paymentRequest)
            }
        }

        // Initialize payment request
        initializePaymentRequest()
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
        
        // Setup close button for animation overlay
        animationCloseButton.setOnClickListener {
            hideAnimationOverlay()
            // If we have a pending token, process it
            pendingToken?.let { token ->
                processPaymentToken(token)
            }
        }
    }

    private fun showAnimationOverlay() {
        nfcAnimationContainer.visibility = View.VISIBLE
        animationCloseButton.visibility = View.GONE
        
        // Start the animation
        if (webViewReady) {
            nfcAnimationWebView.evaluateJavascript("startAnimation()", null)
        }
    }

    private fun hideAnimationOverlay() {
        nfcAnimationContainer.visibility = View.GONE
        animationCloseButton.visibility = View.GONE
        
        // Reset the animation
        if (webViewReady) {
            nfcAnimationWebView.evaluateJavascript("reset()", null)
        }
    }

    private fun showAnimationSuccess(amountText: String) {
        if (webViewReady) {
            val escapedAmount = amountText.replace("'", "\\'")
            nfcAnimationWebView.evaluateJavascript("showSuccess('$escapedAmount')", null)
        }
    }

    private fun showAnimationError(message: String) {
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
                animationCloseButton.visibility = View.VISIBLE
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
        statusText.text = "Preparing payment request..."

        // Get allowed mints
        val mintManager = MintManager.getInstance(this)
        val allowedMints = mintManager.getAllowedMints()
        Log.d(TAG, "Using ${allowedMints.size} allowed mints for payment request")

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
                Toast.makeText(this, "Failed to prepare NDEF payment data", Toast.LENGTH_SHORT).show()
            } else {
                Log.d(TAG, "Created HCE payment request: $hcePaymentRequest")

                // Start HCE service in the background
                val serviceIntent = Intent(this, NdefHostCardEmulationService::class.java)
                startService(serviceIntent)
                setupNdefPayment()
            }
        }

        // Generate ephemeral nostr identity for QR payment
        val eph = NostrKeyPair.generate()
        val nostrPubHex = eph.hexPub
        val nostrSecret = eph.secretKeyBytes

        val relayList = NOSTR_RELAYS.toList()
        val nprofile = com.electricdreams.shellshock.nostr.Nip19.encodeNprofile(
            eph.publicKeyBytes,
            relayList
        )

        Log.d(TAG, "Ephemeral nostr pubkey=$nostrPubHex nprofile=$nprofile")

        // QR-specific PaymentRequest WITH Nostr transport
        qrPaymentRequest = CashuPaymentHelper.createPaymentRequestWithNostr(
            paymentAmount,
            "Payment of $paymentAmount sats",
            allowedMints,
            nprofile
        )

        if (qrPaymentRequest == null) {
            Log.e(TAG, "Failed to create QR payment request with Nostr transport")
            statusText.text = "Error creating payment request"
        } else {
            Log.d(TAG, "Created QR payment request with Nostr: $qrPaymentRequest")

            // Generate and display QR code
            try {
                val qrBitmap = generateQrBitmap(qrPaymentRequest!!, 512)
                qrImageView.setImageBitmap(qrBitmap)
                statusText.text = "Waiting for payment..."
            } catch (e: Exception) {
                Log.e(TAG, "Error generating QR bitmap: ${e.message}", e)
                statusText.text = "Error generating QR code"
            }

            // Start Nostr listener for this ephemeral identity
            setupNostrPayment(nostrSecret, nostrPubHex, relayList)
        }
    }

    private fun setupNdefPayment() {
        val request = hcePaymentRequest ?: return

        // Match original behavior: slight delay before configuring service
        Handler(Looper.getMainLooper()).postDelayed({
            val hceService = NdefHostCardEmulationService.getInstance()
            if (hceService != null) {
                Log.d(TAG, "Setting up NDEF payment with HCE service")

                // Set the payment request to the HCE service with expected amount
                hceService.setPaymentRequest(request, paymentAmount)

                // Set up callback for when a token is received or an error occurs
                hceService.setPaymentCallback(object : NdefHostCardEmulationService.CashuPaymentCallback {
                    override fun onCashuTokenReceived(token: String) {
                        runOnUiThread {
                            try {
                                handlePaymentSuccess(token)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error in NDEF payment callback: ${e.message}", e)
                                handlePaymentError("Error processing NDEF payment: ${e.message}")
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
                            showAnimationOverlay()
                        }
                    }

                    override fun onNfcReadingStopped() {
                        // Don't hide overlay immediately - let the animation complete
                        runOnUiThread {
                            Log.d(TAG, "NFC reading stopped")
                        }
                    }
                })

                Log.d(TAG, "NDEF payment service ready")
            }
        }, 1000)
    }

    private fun setupNostrPayment(
        nostrSecret: ByteArray,
        nostrPubHex: String,
        relayList: List<String>
    ) {
        nostrListener?.stop()
        nostrListener = null

        nostrListener = NostrPaymentListener(
            nostrSecret,
            nostrPubHex,
            paymentAmount,
            MintManager.getInstance(this).getAllowedMints(),
            relayList,
            { token -> runOnUiThread { handlePaymentSuccess(token) } },
            { msg, t -> Log.e(TAG, "NostrPaymentListener error: $msg", t) }
        ).also { it.start() }

        Log.d(TAG, "Nostr payment listener started")
    }

    @Throws(Exception::class)
    private fun generateQrBitmap(text: String, size: Int): Bitmap {
        val hints: MutableMap<EncodeHintType, Any> = mutableMapOf()
        hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.L
        hints[EncodeHintType.MARGIN] = 1 // Small margin so dots aren't cut off

        val qrWriter = QRCodeWriter()
        val rawMatrix: BitMatrix = qrWriter.encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)

        val matrixWidth = rawMatrix.width
        val matrixHeight = rawMatrix.height

        val scale = size / matrixWidth
        val outputWidth = matrixWidth * scale
        val outputHeight = matrixHeight * scale

        val outputBitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(outputBitmap)
        canvas.drawColor(0xFFFFFFFF.toInt())

        val paint = android.graphics.Paint().apply {
            color = 0xFF000000.toInt()
            isAntiAlias = true
        }

        val radius = scale.toFloat() / 2f

        for (x in 0 until matrixWidth) {
            for (y in 0 until matrixHeight) {
                if (rawMatrix[x, y]) {
                    val cx = x * scale + radius
                    val cy = y * scale + radius
                    canvas.drawCircle(cx, cy, radius, paint)
                }
            }
        }

        return outputBitmap
    }

    private fun handlePaymentSuccess(token: String) {
        Log.d(TAG, "Payment successful! Token: $token")
        
        statusText.text = "Payment successful!"
        
        // Store the token to process after user dismisses animation
        pendingToken = token

        // Prepare result intent
        val resultIntent = Intent().apply {
            putExtra(RESULT_EXTRA_TOKEN, token)
            putExtra(RESULT_EXTRA_AMOUNT, paymentAmount)
        }
        setResult(Activity.RESULT_OK, resultIntent)

        // Show success animation with the received amount
        if (nfcAnimationContainer.visibility == View.VISIBLE) {
            showAnimationSuccess(formattedAmount)
        } else {
            // Animation wasn't shown (e.g., QR payment), just finish
            cleanupAndFinish()
        }
    }

    private fun processPaymentToken(token: String) {
        // Clear pending token and finish
        pendingToken = null
        cleanupAndFinish()
    }

    private fun handlePaymentError(errorMessage: String) {
        Log.e(TAG, "Payment error: $errorMessage")
        
        statusText.text = "Payment failed: $errorMessage"

        setResult(Activity.RESULT_CANCELED)

        // Show error animation if overlay is visible
        if (nfcAnimationContainer.visibility == View.VISIBLE) {
            showAnimationError(errorMessage)
        } else {
            Toast.makeText(this, "Payment failed: $errorMessage", Toast.LENGTH_LONG).show()
            Handler(Looper.getMainLooper()).postDelayed({
                cleanupAndFinish()
            }, 3000)
        }
    }

    private fun cancelPayment() {
        Log.d(TAG, "Payment cancelled")
        
        setResult(Activity.RESULT_CANCELED)
        cleanupAndFinish()
    }

    private fun cleanupAndFinish() {
        // Stop Nostr listener
        nostrListener?.let { listener ->
            Log.d(TAG, "Stopping NostrPaymentListener")
            listener.stop()
            nostrListener = null
        }

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
        cleanupAndFinish()
        super.onDestroy()
    }

    private fun sharePaymentRequest(paymentRequest: String) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, paymentRequest)
        }
        startActivity(Intent.createChooser(shareIntent, "Share Payment Request"))
    }

    companion object {
        private const val TAG = "PaymentRequestActivity"

        const val EXTRA_PAYMENT_AMOUNT = "payment_amount"
        const val EXTRA_FORMATTED_AMOUNT = "formatted_amount"
        const val RESULT_EXTRA_TOKEN = "payment_token"
        const val RESULT_EXTRA_AMOUNT = "payment_amount"

        // Nostr relays to use for NIP-17 gift-wrapped DMs
        private val NOSTR_RELAYS = arrayOf(
            "wss://relay.primal.net",
            "wss://relay.damus.io",
            "wss://nos.lol",
            "wss://nostr.mom"
        )
    }
}
