package com.example.shellshock

import android.annotation.SuppressLint
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import android.app.AlertDialog
import android.text.InputType
import android.widget.LinearLayout
import kotlinx.coroutines.suspendCancellableCoroutine
import com.cashujdk.nut00.Proof // Import Proof class

class MainActivity : ComponentActivity() {
    private val TAG = "com.example.shellshock.MainActivity"
    private lateinit var textView: TextView
    private lateinit var nfcScanHint: TextView
    private lateinit var keypadLayout: LinearLayout
    private lateinit var amountDisplay: TextView
    private lateinit var requestPaymentButton: Button
    private var nfcAdapter: NfcAdapter? = null

    private var requestedAmount: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Log.d(TAG, "OnCreate was called")

        // Find UI components
        textView = findViewById(R.id.textView)
        nfcScanHint = findViewById(R.id.nfc_scan_hint)
        keypadLayout = findViewById(R.id.keypad_layout)
        amountDisplay = findViewById(R.id.amount_display)
        requestPaymentButton = findViewById(R.id.request_payment_button)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            textView.text = "NFC is not available on this device."
            nfcScanHint.visibility = View.GONE
            keypadLayout.visibility = View.GONE // Hide keypad if NFC is not available
            textView.visibility = View.VISIBLE // Show NFC error message
            return
        } else {
            nfcScanHint.visibility = View.GONE // Hint is initially hidden, keypad is shown
            keypadLayout.visibility = View.VISIBLE
            textView.visibility = View.GONE // NFC event log is initially hidden
        }

        setupKeypadListeners()

        // Handle NFC intent if the app was launched by an NFC tag
        if (NfcAdapter.ACTION_TECH_DISCOVERED == intent.action) {
            handleNfcIntent(intent)
        }
    }

    private fun setupKeypadListeners() {
        val buttons = listOf(
            R.id.button_0, R.id.button_1, R.id.button_2, R.id.button_3,
            R.id.button_4, R.id.button_5, R.id.button_6, R.id.button_7,
            R.id.button_8, R.id.button_9
        )

        buttons.forEach { id ->
            findViewById<Button>(id).setOnClickListener {
                val digit = (it as Button).text.toString().toLong()
                if (requestedAmount == 0L) {
                    requestedAmount = digit
                } else {
                    requestedAmount = requestedAmount * 10 + digit
                }
                amountDisplay.text = requestedAmount.toString()
            }
        }

        findViewById<Button>(R.id.button_clear).setOnClickListener {
            requestedAmount = 0
            amountDisplay.text = "0"
        }

        findViewById<Button>(R.id.button_del).setOnClickListener {
            requestedAmount /= 10
            amountDisplay.text = requestedAmount.toString()
        }
    }

    override fun onResume() {
        super.onResume()
        nfcAdapter?.let {
            val pendingIntent = android.app.PendingIntent.getActivity(
                this, 0, Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                android.app.PendingIntent.FLAG_MUTABLE
            )
            val techLists = arrayOf(arrayOf(IsoDep::class.java.name))
            it.enableForegroundDispatch(this, pendingIntent, null, techLists)
            Log.d(TAG, "Foreground dispatch enabled.")
        }
        // When resuming, show keypad and hide NFC log/hint
        keypadLayout.visibility = View.VISIBLE
        nfcScanHint.visibility = View.GONE
        textView.visibility = View.GONE
        textView.text = "" // Clear previous card info
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
        Log.d(TAG, "Foreground dispatch disabled.")
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null && NfcAdapter.ACTION_TECH_DISCOVERED == intent.action) {
            handleNfcIntent(intent)
        }
    }

    @SuppressLint("SetTextI19n")
    private fun handleNfcIntent(intent: Intent) {
        val tag: Tag? = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        if (tag != null) {
            // Hide keypad and show NFC log when a card is detected
            keypadLayout.visibility = View.GONE
            nfcScanHint.visibility = View.GONE
            textView.visibility = View.VISIBLE

            textView.text = "NFC Tag discovered: ${tag.id?.toHexString()}"
            Log.d(TAG, "NFC Tag discovered: ${tag.id?.toHexString()}")

            lifecycleScope.launch(Dispatchers.IO) {
                var satocashClient: SatocashNfcClient? = null
                var satocashWallet: SatocashWallet? = null
                try {
                    satocashClient = SatocashNfcClient(tag)
                    satocashClient.connect()
                    satocashWallet = SatocashWallet(satocashClient)

                    satocashClient.selectApplet(SatocashNfcClient.SATOCASH_AID)
                    withContext(Dispatchers.Main) {
                        textView.text = "Satocash Applet found and selected!"
                    }
                    Log.d(TAG, "Satocash Applet selected.")

                    satocashClient.initSecureChannel()
                    withContext(Dispatchers.Main) {
                        textView.append("\nSecure Channel Initialized!")
                    }
                    Log.d(TAG, "Secure Channel Initialized.")

                    val status = satocashClient.getStatus()
                    withContext(Dispatchers.Main) {
                        textView.append("\nCard Status: ${status["applet_version"]}, PIN tries: ${status["pin_tries_remaining"]}")
                    }
                    Log.d(TAG, "Card Status: $status")

                    val pin = withContext(Dispatchers.Main) {
                        showPinInputDialog()
                    }

                    if (pin != null) {
                        try {
                            satocashWallet.authenticatePIN(pin).join() // Authenticate using SatocashWallet
                            withContext(Dispatchers.Main) {
                                textView.append("\nPIN Verified! Card Ready.")
                            }
                            Log.d(TAG, "PIN Verified.")

                            // Now, handle the request payment button click
                            withContext(Dispatchers.Main) {
                                requestPaymentButton.setOnClickListener {
                                    if (requestedAmount > 0) {
                                        lifecycleScope.launch(Dispatchers.IO) {
                                            try {
                                                withContext(Dispatchers.Main) {
                                                    textView.append("\nRequesting payment for $requestedAmount SAT...")
                                                }
                                                val receivedProofs: List<Proof> = satocashWallet.getPayment(requestedAmount, "SAT").join()
                                                withContext(Dispatchers.Main) {
                                                    textView.append("\nPayment successful! Received ${receivedProofs.size} proofs.")
                                                    receivedProofs.forEach { proof ->
                                                        textView.append("\n  Proof: Amount=${proof.amount}, Keyset=${proof.keysetId}")
                                                    }
                                                }
                                                Log.d(TAG, "Payment successful. Received proofs: $receivedProofs")
                                            } catch (e: Exception) {
                                                withContext(Dispatchers.Main) {
                                                    textView.append("\nPayment failed: ${e.message}")
                                                }
                                                Log.e(TAG, "Payment failed: ${e.message}", e)
                                            }
                                        }
                                    } else {
                                        withContext(Dispatchers.Main) {
                                            textView.append("\nPlease enter an amount greater than 0.")
                                        }
                                    }
                                }
                                // Trigger the button click programmatically if you want it to happen immediately after PIN
                                // requestPaymentButton.performClick()
                            }

                        } catch (e: RuntimeException) { // Catch RuntimeException from CompletableFuture.join()
                            val cause = e.cause
                            if (cause is SatocashNfcClient.SatocashException) {
                                withContext(Dispatchers.Main) {
                                    textView.append("\nPIN Verification Failed: ${cause.message} (SW: ${String.format("0x%04X", cause.sw)})")
                                }
                                Log.e(TAG, "PIN Verification Failed: ${cause.message} (SW: ${String.format("0x%04X", cause.sw)})")
                            } else {
                                withContext(Dispatchers.Main) {
                                    textView.append("\nAuthentication Failed: ${e.message}")
                                }
                                Log.e(TAG, "Authentication Failed: ${e.message}", e)
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            textView.append("\nPIN entry cancelled.")
                        }
                        Log.d(TAG, "PIN entry cancelled by user.")
                    }

                } catch (e: IOException) {
                    withContext(Dispatchers.Main) {
                        textView.text = "NFC Communication Error: ${e.message}"
                    }
                    Log.e(TAG, "NFC Communication Error: ${e.message}", e)
                } catch (e: SatocashNfcClient.SatocashException) {
                    withContext(Dispatchers.Main) {
                        textView.text = "Satocash Card Error: ${e.message} (SW: ${String.format("0x%04X", e.sw)})"
                    }
                    Log.e(TAG, "Satocash Card Error: ${e.message} (SW: ${String.format("0x%04X", e.sw)})", e)
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        textView.text = "An unexpected error occurred: ${e.message}"
                    }
                    Log.e(TAG, "An unexpected error occurred: ${e.message}", e)
                } finally {
                    try {
                        satocashClient?.close()
                    } catch (e: IOException) {
                        Log.e(TAG, "Error closing IsoDep connection: ${e.message}", e)
                    }
                    // Show keypad and hide NFC log/hint when card interaction is finished or an error occurred
                    withContext(Dispatchers.Main) {
                        keypadLayout.visibility = View.VISIBLE
                        nfcScanHint.visibility = View.GONE // Ensure hint stays hidden if keypad is visible
                        textView.visibility = View.GONE
                    }
                }
            }
        }
    }

    private suspend fun showPinInputDialog(): String? = withContext(Dispatchers.Main) {
        return@withContext suspendCancellableCoroutine { continuation ->
            val builder = AlertDialog.Builder(this@MainActivity)
            builder.setTitle("Enter PIN")

            val input = EditText(this@MainActivity)
            input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            input.hint = "PIN"

            val layout = LinearLayout(this@MainActivity)
            layout.orientation = LinearLayout.VERTICAL
            layout.setPadding(50, 20, 50, 20) // Add some padding
            layout.addView(input)

            // Add a simple numeric keypad
            val keypadLayout = LinearLayout(this@MainActivity)
            keypadLayout.orientation = LinearLayout.VERTICAL
            keypadLayout.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            val buttons = arrayOf(
                arrayOf("1", "2", "3"),
                arrayOf("4", "5", "6"),
                arrayOf("7", "8", "9"),
                arrayOf("", "0", "DEL") // Empty string for spacing, DEL for backspace
            )

            for (row in buttons) {
                val rowLayout = LinearLayout(this@MainActivity)
                rowLayout.orientation = LinearLayout.HORIZONTAL
                rowLayout.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { weight = 1.0f }

                for (text in row) {
                    val button = Button(this@MainActivity)
                    button.text = text
                    button.layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { weight = 1.0f }
                    button.setOnClickListener {
                        when (text) {
                            "DEL" -> {
                                if (input.text.isNotEmpty()) {
                                    input.text = input.text.delete(input.text.length - 1, input.text.length)
                                }
                            }
                            "" -> { /* Do nothing for empty button */ }
                            else -> {
                                input.append(text)
                            }
                        }
                    }
                    rowLayout.addView(button)
                }
                keypadLayout.addView(rowLayout)
            }
            layout.addView(keypadLayout)

            builder.setView(layout)

            builder.setPositiveButton("OK") { dialog, _ ->
                val pin = input.text.toString()
                if (continuation.isActive) {
                    continuation.resume(pin) {}
                }
                dialog.dismiss()
            }
            builder.setNegativeButton("Cancel") { dialog, _ ->
                if (continuation.isActive) {
                    continuation.resume(null) {}
                }
                dialog.cancel()
            }

            builder.setOnCancelListener {
                if (continuation.isActive) {
                    continuation.resume(null) {}
                }
            }

            val dialog = builder.create()
            dialog.show()
        }
    }

    // Extension function to convert ByteArray to Hex String for logging
    private fun ByteArray.toHexString(): String =
        joinToString(separator = "") { eachByte -> "%02x".format(eachByte) }
}
