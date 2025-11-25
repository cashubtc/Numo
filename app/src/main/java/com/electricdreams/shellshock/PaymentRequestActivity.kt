package com.electricdreams.shellshock

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
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
import com.electricdreams.shellshock.core.cashu.CashuWalletManager
import com.electricdreams.shellshock.core.model.Amount
import com.electricdreams.shellshock.core.model.Amount.Currency
import com.electricdreams.shellshock.core.util.MintManager
import com.electricdreams.shellshock.core.worker.BitcoinPriceWorker
import com.electricdreams.shellshock.core.util.CurrencyManager
import com.electricdreams.shellshock.ndef.CashuPaymentHelper
import com.electricdreams.shellshock.ndef.NdefHostCardEmulationService
import com.electricdreams.shellshock.nostr.NostrKeyPair
import com.electricdreams.shellshock.nostr.NostrPaymentListener
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.cashudevkit.Amount as CdkAmount
import org.cashudevkit.CurrencyUnit
import org.cashudevkit.MintQuote
import org.cashudevkit.MintUrl
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class PaymentRequestActivity : AppCompatActivity() {

    private lateinit var cashuQrImageView: ImageView
    private lateinit var lightningQrImageView: ImageView
    private lateinit var cashuTab: TextView
    private lateinit var lightningTab: TextView
    private lateinit var largeAmountDisplay: TextView
    private lateinit var convertedAmountDisplay: TextView
    private lateinit var statusText: TextView
    private lateinit var closeButton: View
    private lateinit var shareButton: View
    private lateinit var nfcReadingOverlay: android.view.View

    private var paymentAmount: Long = 0
    private var bitcoinPriceWorker: BitcoinPriceWorker? = null
    private var hcePaymentRequest: String? = null
    private var qrPaymentRequest: String? = null
    private var nostrListener: NostrPaymentListener? = null

    // Lightning minting state
    private var lightningMintQuote: MintQuote? = null
    private var lightningMintJob: Job? = null

    private val uiScope = CoroutineScope(Dispatchers.Main)

    // Shared OkHttp client for mint WebSocket connections
    private val lightningWsClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS) // no timeout, rely on WS pings
            .build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_payment_request)

        // Initialize views
        cashuQrImageView = findViewById(R.id.payment_request_qr)
        lightningQrImageView = findViewById(R.id.lightning_qr)
        cashuTab = findViewById(R.id.cashu_tab)
        lightningTab = findViewById(R.id.lightning_tab)
        largeAmountDisplay = findViewById(R.id.large_amount_display)
        convertedAmountDisplay = findViewById(R.id.converted_amount_display)
        statusText = findViewById(R.id.payment_status_text)
        closeButton = findViewById(R.id.close_button)
        shareButton = findViewById(R.id.share_button)

        // Set up tab listeners
        setupTabs()
        nfcReadingOverlay = findViewById(R.id.nfc_reading_overlay)

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
        val formattedAmountString = intent.getStringExtra(EXTRA_FORMATTED_AMOUNT)
            ?: Amount(paymentAmount, Currency.BTC).toString()

        // Display amount (without "Pay" prefix since it's in the label above)
        largeAmountDisplay.text = formattedAmountString

        // Calculate and display converted amount
        updateConvertedAmount(formattedAmountString)

        // Set up buttons
        closeButton.setOnClickListener {
            Log.d(TAG, "Payment cancelled by user")
            cancelPayment()
        }

        shareButton.setOnClickListener {
            // By default, share the Cashu (Nostr) payment request; fall back to Lightning invoice
            val toShare = qrPaymentRequest ?: lightningMintQuote?.request
            if (toShare != null) {
                sharePaymentRequest(toShare)
            } else {
                Toast.makeText(this, "Nothing to share yet", Toast.LENGTH_SHORT).show()
            }
        }

        // Initialize all payment modes (NDEF, Nostr, Lightning)
        initializePaymentRequest()
    }

    private fun setupTabs() {
        // Default: show Cashu (Nostr) QR; Lightning QR becomes visible only when tab is selected
        selectCashuTab()

        lightningTab.setOnClickListener { selectLightningTab() }
        cashuTab.setOnClickListener { selectCashuTab() }
    }

    private fun selectLightningTab() {
        // Visual state
        lightningTab.setTextColor(resources.getColor(R.color.color_bg_white, theme))
        lightningTab.setBackgroundResource(R.drawable.bg_button_primary_green)
        cashuTab.setTextColor(resources.getColor(R.color.color_text_secondary, theme))
        cashuTab.setBackgroundResource(android.R.color.transparent)

        // QR visibility
        lightningQrImageView.visibility = View.VISIBLE
        cashuQrImageView.visibility = View.INVISIBLE

        // Start lightning quote flow once when tab first selected and quote not created yet
        if (lightningMintQuote == null) {
            startLightningMintFlow()
        }
    }

    private fun selectCashuTab() {
        // Visual state
        cashuTab.setTextColor(resources.getColor(R.color.color_bg_white, theme))
        cashuTab.setBackgroundResource(R.drawable.bg_button_primary_green)
        lightningTab.setTextColor(resources.getColor(R.color.color_text_secondary, theme))
        lightningTab.setBackgroundResource(android.R.color.transparent)

        // QR visibility
        cashuQrImageView.visibility = View.VISIBLE
        lightningQrImageView.visibility = View.INVISIBLE

        // Status text mainly controlled by Nostr / HCE flow
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

        // QR-specific PaymentRequest WITH Nostr transport (Cashu over Nostr)
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

            // Generate and display Cashu QR code
            try {
                val qrBitmap = generateQrBitmap(qrPaymentRequest!!, 512)
                cashuQrImageView.setImageBitmap(qrBitmap)
                statusText.text = "Waiting for payment..."
            } catch (e: Exception) {
                Log.e(TAG, "Error generating Cashu QR bitmap: ${e.message}", e)
                statusText.text = "Error generating QR code"
            }

            // Start Nostr listener for this ephemeral identity
            setupNostrPayment(nostrSecret, nostrPubHex, relayList)
        }

        // Start Lightning flow in parallel with Nostr/HCE
        // The invoice QR itself is only shown when Lightning tab is selected.
        startLightningMintFlow()
    }

    private fun startLightningMintFlow() {
        val wallet = CashuWalletManager.getWallet()
        if (wallet == null) {
            Log.w(TAG, "MultiMintWallet not ready, skipping Lightning for now")
            return
        }

        val allowedMints = MintManager.getInstance(this).getAllowedMints()
        if (allowedMints.isEmpty()) {
            Log.w(TAG, "No allowed mints configured, cannot request Lightning mint quote")
            return
        }

        // For now pick the first allowed mint; could be randomized/rotated later
        val mintUrl = try {
            MintUrl(allowedMints.first())
        } catch (t: Throwable) {
            Log.e(TAG, "Invalid mint URL for Lightning mint: ${allowedMints.first()}", t)
            return
        }

        lightningMintJob?.cancel()
        lightningMintJob = uiScope.launch(Dispatchers.IO) {
            try {
                // CDK Amount is in minor units of wallet's CurrencyUnit (we constructed wallet in sats)
                val quoteAmount = CdkAmount(paymentAmount.toULong())

                Log.d(TAG, "Requesting Lightning mint quote from ${mintUrl.toString()} for $paymentAmount sats")
                val quote = wallet.mintQuote(mintUrl, quoteAmount, "Shellshock POS payment of $paymentAmount sats")
                lightningMintQuote = quote

                val bolt11 = quote.request
                Log.d(TAG, "Received Lightning mint quote id=${quote.id} bolt11=$bolt11")

                // Update UI with invoice QR on main thread
                launch(Dispatchers.Main) {
                    try {
                        val qrBitmap = generateQrBitmap(bolt11, 512)
                        lightningQrImageView.setImageBitmap(qrBitmap)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error generating Lightning QR bitmap: ${e.message}", e)
                    }
                }

                // Subscribe to mint quote state updates via WebSocket (NUT-17)
                try {
                    Log.d(TAG, "Subscribing to Lightning mint quote state via WebSocket for id=${quote.id}")
                    awaitLightningMintQuotePaid(mintUrl, quote.id)
                } catch (ce: CancellationException) {
                    // Job was cancelled (e.g. user cancelled, or another payment path succeeded)
                    Log.d(TAG, "Lightning mint flow cancelled while waiting on WebSocket: ${ce.message}")
                    return@launch
                }

                Log.d(TAG, "Mint quote ${quote.id} is paid according to WS, calling wallet.mint")
                val proofs = wallet.mint(mintUrl, quote.id, null)
                Log.d(TAG, "Lightning mint completed with ${proofs.size} proofs (Lightning payment path)")

                launch(Dispatchers.Main) {
                    handleLightningPaymentSuccess()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in Lightning mint flow: ${e.message}", e)
                // Do not immediately fail the whole payment; NFC or Nostr may still succeed.
                // Only surface a toast if Lightning tab is currently active.
                launch(Dispatchers.Main) {
                    if (lightningQrImageView.visibility == View.VISIBLE) {
                        Toast.makeText(
                            this@PaymentRequestActivity,
                            "Lightning payment failed: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
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
                            Log.d(TAG, "NFC reading started - showing overlay")
                            nfcReadingOverlay.visibility = android.view.View.VISIBLE
                        }
                    }

                    override fun onNfcReadingStopped() {
                        runOnUiThread {
                            Log.d(TAG, "NFC reading stopped - hiding overlay")
                            nfcReadingOverlay.visibility = android.view.View.GONE
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

        statusText.visibility = View.VISIBLE
        statusText.text = "Payment successful!"

        val resultIntent = Intent().apply {
            putExtra(RESULT_EXTRA_TOKEN, token)
            putExtra(RESULT_EXTRA_AMOUNT, paymentAmount)
        }
        setResult(Activity.RESULT_OK, resultIntent)

        cleanupAndFinish()
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
        statusText.text = "Payment successful!"

        val resultIntent = Intent().apply {
            putExtra(RESULT_EXTRA_TOKEN, "")
            putExtra(RESULT_EXTRA_AMOUNT, paymentAmount)
        }
        setResult(Activity.RESULT_OK, resultIntent)

        cleanupAndFinish()
    }

    private fun handlePaymentError(errorMessage: String) {
        Log.e(TAG, "Payment error: $errorMessage")

        statusText.visibility = View.VISIBLE
        statusText.text = "Payment failed: $errorMessage"
        Toast.makeText(this, "Payment failed: $errorMessage", Toast.LENGTH_LONG).show()

        setResult(Activity.RESULT_CANCELED)

        Handler(Looper.getMainLooper()).postDelayed({
            cleanupAndFinish()
        }, 3000)
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

        // Stop Lightning mint job
        lightningMintJob?.cancel()
        lightningMintJob = null

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
        startActivity(Intent.createChooser(shareIntent, "Share Payment Request"))
    }

    /**
     * Build the mint's WebSocket URL as `<scheme>/v1/ws` based on the MintUrl.
     *
     * If the mint URL is `https://mint.com` this returns `wss://mint.com/v1/ws`.
     * If it includes a path (e.g. `https://mint.com/Bitcoin`) we append `/v1/ws`
     * after that path: `wss://mint.com/Bitcoin/v1/ws`.
     */
    private fun buildMintWsUrl(mintUrl: MintUrl): String {
        val base = mintUrl.url.removeSuffix("/")
        val wsBase = when {
            base.startsWith("https://", ignoreCase = true) ->
                "wss://" + base.removePrefix("https://")
            base.startsWith("http://", ignoreCase = true) ->
                "ws://" + base.removePrefix("http://")
            base.startsWith("wss://", ignoreCase = true) ||
                base.startsWith("ws://", ignoreCase = true) -> base
            else -> "wss://$base"
        }
        return "$wsBase/v1/ws"
    }

    /**
     * Suspend until the given Lightning mint quote is reported as paid via the
     * mint's WebSocket (NUT-17) subscription.
     *
     * We subscribe with kind = "bolt11_mint_quote" and a single filter: the quote id.
     * On `state == "PAID"` (or `"ISSUED"` if we attached late), we resume.
     *
     * Cancellation of the coroutine will unsubscribe and close the WebSocket,
     * which is how we shut this down when another payment path wins or the user
     * cancels the checkout.
     */
    private suspend fun awaitLightningMintQuotePaid(
        mintUrl: MintUrl,
        quoteId: String
    ) = suspendCancellableCoroutine<Unit> { cont ->
        val wsUrl = buildMintWsUrl(mintUrl)
        Log.d(TAG, "Connecting to mint WebSocket at $wsUrl for quoteId=$quoteId")

        val request = Request.Builder().url(wsUrl).build()
        val gson = Gson()
        val subId = UUID.randomUUID().toString()
        var nextRequestId = 0
        var webSocket: WebSocket? = null

        fun sendUnsubscribe(ws: WebSocket) {
            val params = mapOf("subId" to subId)
            val msg = mapOf(
                "jsonrpc" to "2.0",
                "id" to ++nextRequestId,
                "method" to "unsubscribe",
                "params" to params
            )
            val json = gson.toJson(msg)
            Log.d(TAG, "Sending unsubscribe for subId=$subId: $json")
            ws.send(json)
        }

        val listener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "Mint WebSocket open: $wsUrl")
                webSocket = ws
                val params = mapOf(
                    "kind" to "bolt11_mint_quote",
                    "subId" to subId,
                    "filters" to listOf(quoteId),
                )
                val msg = mapOf(
                    "jsonrpc" to "2.0",
                    "id" to nextRequestId,
                    "method" to "subscribe",
                    "params" to params,
                )
                val json = gson.toJson(msg)
                Log.d(TAG, "Sending subscribe for subId=$subId, quoteId=$quoteId: $json")
                ws.send(json)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                Log.v(TAG, "Mint WS message: $text")
                try {
                    val root = gson.fromJson(text, JsonObject::class.java) ?: return

                    if (root.has("error")) {
                        val errorObj = root.getAsJsonObject("error")
                        val code = errorObj["code"]?.asInt
                        val message = errorObj["message"]?.asString
                        val ex = Exception("Mint WS error code=$code message=$message")
                        if (!cont.isCompleted) {
                            cont.resumeWithException(ex)
                        }
                        ws.close(1000, "error")
                        return
                    }

                    if (root.has("result")) {
                        // subscribe/unsubscribe ACK; nothing to do here for now
                        return
                    }

                    val method = root.get("method")?.asString ?: return
                    if (method != "subscribe") return

                    val params = root.getAsJsonObject("params") ?: return
                    val payload = params.getAsJsonObject("payload") ?: return
                    val state = payload.get("state")?.asString ?: return

                    Log.d(TAG, "Mint quote update for quoteId=$quoteId state=$state")

                    if (state.equals("PAID", ignoreCase = true) ||
                        state.equals("ISSUED", ignoreCase = true)
                    ) {
                        if (!cont.isCompleted) {
                            cont.resume(Unit)
                        }
                        try {
                            sendUnsubscribe(ws)
                        } catch (_: Throwable) {
                        }
                        ws.close(1000, "quote paid")
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Error parsing mint WS message: ${t.message}", t)
                    if (!cont.isCompleted) {
                        cont.resumeWithException(t)
                    }
                    ws.close(1000, "parse error")
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Mint WS failure: ${t.message}", t)
                if (!cont.isCompleted) {
                    cont.resumeWithException(t)
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Mint WS closed: code=$code reason=$reason")
                if (!cont.isCompleted && !cont.isCancelled) {
                    cont.resumeWithException(
                        CancellationException("Mint WS closed before quote paid: $code $reason")
                    )
                }
            }
        }

        val ws = lightningWsClient.newWebSocket(request, listener)
        webSocket = ws

        cont.invokeOnCancellation {
            Log.d(TAG, "Coroutine cancelled while waiting for mint quote; closing WS")
            try {
                webSocket?.let { socket ->
                    try {
                        sendUnsubscribe(socket)
                    } catch (_: Throwable) {
                    }
                    socket.close(1000, "cancelled")
                }
            } catch (_: Throwable) {
            }
        }
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
