package com.electricdreams.shellshock

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MenuItem
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.electricdreams.shellshock.core.model.Amount
import com.electricdreams.shellshock.core.model.Amount.Currency
import com.electricdreams.shellshock.core.util.MintManager
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
    private lateinit var statusText: TextView
    private lateinit var closeButton: android.view.View
    private lateinit var shareButton: android.view.View

    private var paymentAmount: Long = 0
    private var hcePaymentRequest: String? = null
    private var qrPaymentRequest: String? = null
    private var nostrListener: NostrPaymentListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_payment_request)

        // Initialize views
        qrImageView = findViewById(R.id.payment_request_qr)
        largeAmountDisplay = findViewById(R.id.large_amount_display)
        statusText = findViewById(R.id.payment_status_text)
        closeButton = findViewById(R.id.close_button)
        shareButton = findViewById(R.id.share_button)

        // Get payment amount from intent
        paymentAmount = intent.getLongExtra(EXTRA_PAYMENT_AMOUNT, 0)

        if (paymentAmount <= 0) {
            Log.e(TAG, "Invalid payment amount: $paymentAmount")
            Toast.makeText(this, "Invalid payment amount", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Display payment amount
        val formattedAmount = Amount(paymentAmount, Currency.BTC).toString()
        largeAmountDisplay.text = "Pay $formattedAmount"

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

        val resultIntent = Intent().apply {
            putExtra(RESULT_EXTRA_TOKEN, token)
            putExtra(RESULT_EXTRA_AMOUNT, paymentAmount)
        }
        setResult(Activity.RESULT_OK, resultIntent)

        cleanupAndFinish()
    }

    private fun handlePaymentError(errorMessage: String) {
        Log.e(TAG, "Payment error: $errorMessage")
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

    companion object {
        private const val TAG = "PaymentRequestActivity"

        const val EXTRA_PAYMENT_AMOUNT = "payment_amount"
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
