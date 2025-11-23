package com.electricdreams.shellshock

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.NavUtils
import com.google.android.material.snackbar.Snackbar

class TopUpActivity : AppCompatActivity() {

    private lateinit var proofTokenEditText: EditText
    private lateinit var topUpSubmitButton: Button
    private var nfcDialog: AlertDialog? = null
    private var rescanDialog: AlertDialog? = null
    private var processingDialog: AlertDialog? = null
    private var nfcAdapter: NfcAdapter? = null
    private var satocashClient: SatocashNfcClient? = null
    private var satocashWallet: SatocashWallet? = null
    private var pendingProofToken: String? = null
    private var savedPin: String? = null
    private var waitingForRescan: Boolean = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var rootView: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.Theme_Shellshock)
        setContentView(R.layout.activity_top_up)

        // Set up back button
        findViewById<View?>(R.id.back_button)?.setOnClickListener {
            NavUtils.navigateUpFromSameTask(this)
        }

        // Set up the toolbar (hidden but kept for compatibility if needed)
        val toolbar: Toolbar? = findViewById(R.id.toolbar)
        toolbar?.let {
            setSupportActionBar(it)
            supportActionBar?.apply {
                setDisplayHomeAsUpEnabled(true)
                title = "Top Up"
            }
        }

        rootView = findViewById(android.R.id.content)
        proofTokenEditText = findViewById(R.id.top_up_amount_edit_text)
        topUpSubmitButton = findViewById(R.id.top_up_submit_button)

        // Handle incoming share intent
        intent?.let { incomingIntent ->
            val action = incomingIntent.action
            val type = incomingIntent.type
            if (Intent.ACTION_SEND == action && type == "text/plain") {
                val sharedText = incomingIntent.getStringExtra(Intent.EXTRA_TEXT)
                if (!sharedText.isNullOrEmpty()) {
                    pendingProofToken = sharedText
                    proofTokenEditText.setText(sharedText)
                    showStatusMessage("Token ready to be imported", success = true)
                    showNfcDialog()
                }
            }
        }

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            showStatusMessage("NFC is not available on this device", success = false)
            topUpSubmitButton.isEnabled = false
        }

        topUpSubmitButton.setOnClickListener {
            val proofToken = proofTokenEditText.text.toString()
            if (proofToken.isNotEmpty()) {
                pendingProofToken = proofToken
                showStatusMessage("Tap your card to import the proofs", success = true)
                showNfcDialog()
            } else {
                showStatusMessage("Please enter a Cashu proof token", success = false)
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if (item.itemId == android.R.id.home) {
            NavUtils.navigateUpFromSameTask(this)
            true
        } else {
            super.onOptionsItemSelected(item)
        }
    }

    private fun showStatusMessage(message: String, success: Boolean) {
        mainHandler.post {
            val snackbar = Snackbar.make(rootView, message, Snackbar.LENGTH_LONG)
            val snackbarView = snackbar.view
            val textView: TextView = snackbarView.findViewById(com.google.android.material.R.id.snackbar_text)
            textView.setTextColor(Color.WHITE)
            snackbarView.setBackgroundColor(
                if (success) Color.parseColor("#4CAF50") else Color.parseColor("#F44336")
            )
            snackbar.show()
        }
    }

    private fun showNfcDialog() {
        mainHandler.post {
            val builder = AlertDialog.Builder(this, R.style.Theme_Shellshock)
            val dialogView = layoutInflater.inflate(R.layout.dialog_nfc_modern, null)
            builder.setView(dialogView)

            dialogView.findViewById<TextView>(R.id.nfc_amount_display)?.text = "Ready to import proofs"

            builder.setCancelable(true)
            nfcDialog = builder.create().also { it.show() }
        }
    }

    private fun showRescanDialog() {
        mainHandler.post {
            val builder = AlertDialog.Builder(this, R.style.Theme_Shellshock)
            val dialogView = layoutInflater.inflate(R.layout.dialog_nfc_modern, null)
            builder.setView(dialogView)

            dialogView.findViewById<TextView>(R.id.nfc_amount_display)?.text = "Ready to import proofs"

            dialogView.findViewById<TextView>(R.id.nfc_dialog_title)?.text = "Scan Card Again"

            dialogView.findViewById<TextView>(R.id.nfc_hint_text)?.apply {
                text = "PIN accepted. Please scan your card again to complete import."
                visibility = View.VISIBLE
            }

            builder.setCancelable(true)
            builder.setOnCancelListener {
                savedPin = null
                waitingForRescan = false
            }

            rescanDialog = builder.create().also { it.show() }
        }
    }

    private fun showProcessingDialog() {
        mainHandler.post {
            val builder = AlertDialog.Builder(this, R.style.Theme_Shellshock)
            val dialogView = layoutInflater.inflate(R.layout.dialog_nfc_modern, null)
            builder.setView(dialogView)

            dialogView.findViewById<TextView>(R.id.nfc_dialog_title)?.text = "Processing Import"
            dialogView.findViewById<TextView>(R.id.nfc_amount_display)?.text = "Importing proofs..."

            builder.setCancelable(false)
            processingDialog = builder.create().also { it.show() }
        }
    }

    private fun showPinDialog(callback: (String?) -> Unit) {
        mainHandler.post {
            val builder = AlertDialog.Builder(this)
            builder.setTitle("Enter PIN")

            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val density = resources.displayMetrics.density
                val padding = (50 * density).toInt()
                val paddingVertical = (20 * density).toInt()
                setPadding(padding, paddingVertical, padding, paddingVertical)
            }

            val input = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                hint = "PIN"
            }
            layout.addView(input)

            val keypadLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val buttons = arrayOf(
                arrayOf("1", "2", "3"),
                arrayOf("4", "5", "6"),
                arrayOf("7", "8", "9"),
                arrayOf("", "0", "DEL")
            )

            for (row in buttons) {
                val rowLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { weight = 1.0f }
                }

                for (text in row) {
                    val button = Button(this).apply {
                        setText(text)
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { weight = 1.0f }
                        setOnClickListener {
                            when (text) {
                                "DEL" -> if (input.length() > 0) {
                                    input.text.delete(input.length() - 1, input.length())
                                }
                                "" -> Unit
                                else -> input.append(text)
                            }
                        }
                    }
                    rowLayout.addView(button)
                }

                keypadLayout.addView(rowLayout)
            }

            val buttonLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (20 * resources.displayMetrics.density).toInt() }
            }

            val cancelButton = Button(this).apply {
                text = "Cancel"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                    rightMargin = (8 * resources.displayMetrics.density).toInt()
                }
            }

            val okButton = Button(this).apply {
                text = "OK"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                    leftMargin = (8 * resources.displayMetrics.density).toInt()
                }
            }

            buttonLayout.addView(cancelButton)
            buttonLayout.addView(okButton)

            layout.addView(keypadLayout)
            layout.addView(buttonLayout)
            builder.setView(layout)

            val dialog = builder.create()

            cancelButton.setOnClickListener {
                dialog.cancel()
                callback(null)
            }

            okButton.setOnClickListener {
                val pin = input.text.toString()
                dialog.dismiss()
                callback(pin)
            }

            dialog.setOnCancelListener { callback(null) }

            dialog.show()
        }
    }

    override fun onResume() {
        super.onResume()
        nfcAdapter?.let { adapter ->
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_MUTABLE
            )
            val techList = arrayOf(arrayOf(IsoDep::class.java.name))
            adapter.enableForegroundDispatch(this, pendingIntent, null, techList)
        }
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (NfcAdapter.ACTION_TECH_DISCOVERED == intent.action) {
            val tag: Tag? = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            if (tag != null) {
                handleNfcImport(tag)
            }
        }
    }

    private fun handleNfcImport(tag: Tag) {
        val token = pendingProofToken
        if (token.isNullOrEmpty()) {
            showStatusMessage("No proof token set to import", success = false)
            return
        }

        if (waitingForRescan && !savedPin.isNullOrEmpty()) {
            processImportWithSavedPin(tag)
            return
        }

        waitingForRescan = false

        Thread {
            var tempClient: SatocashNfcClient? = null
            try {
                tempClient = SatocashNfcClient(tag)
                tempClient.connect()
                Log.d(TAG, "Connected to NFC card")

                satocashClient = tempClient
                satocashWallet = SatocashWallet(satocashClient!!)

                satocashClient!!.selectApplet(SatocashNfcClient.SATOCASH_AID)
                Log.d(TAG, "Satocash Applet found and selected!")

                satocashClient!!.initSecureChannel()
                Log.d(TAG, "Secure Channel Initialized!")

                try {
                    val importedCount = satocashWallet!!.importProofsFromToken(token).join()
                    showStatusMessage("Success: Imported $importedCount proofs", success = true)

                    mainHandler.post {
                        nfcDialog?.takeIf { it.isShowing }?.dismiss()
                        pendingProofToken = ""
                        proofTokenEditText.setText("")
                    }
                    return@Thread
                } catch (e: RuntimeException) {
                    val cause = e.cause
                    if (cause is SatocashNfcClient.SatocashException) {
                        val statusWord = cause.sw
                        Log.d(TAG, String.format("Status Word received: 0x%04X", statusWord))

                        if (statusWord == 0x9C06) {
                            Log.d(TAG, "PIN authentication needed")

                            try {
                                satocashClient?.let { client ->
                                    client.close()
                                    Log.d(TAG, "NFC connection closed before PIN entry")
                                    satocashClient = null
                                }
                            } catch (ioe: java.io.IOException) {
                                Log.e(TAG, "Error closing NFC connection before PIN entry: ${ioe.message}")
                            }

                            val pinFuture = java.util.concurrent.CompletableFuture<String?>()
                            mainHandler.post {
                                nfcDialog?.takeIf { it.isShowing }?.dismiss()
                                showPinDialog { pin -> pinFuture.complete(pin) }
                            }

                            val enteredPin = pinFuture.join()
                            if (!enteredPin.isNullOrEmpty()) {
                                savedPin = enteredPin
                                waitingForRescan = true
                                mainHandler.post { showRescanDialog() }
                            } else {
                                showStatusMessage("Operation cancelled", success = false)
                            }
                            return@Thread
                        } else {
                            showStatusMessage("Card error: ${cause.message}", success = false)
                        }
                    } else {
                        showStatusMessage("Error: ${e.message}", success = false)
                    }
                }
            } catch (e: java.io.IOException) {
                showStatusMessage("NFC Communication Error: ${e.message}", success = false)
            } catch (e: SatocashNfcClient.SatocashException) {
                showStatusMessage("Satocash Card Error: ${e.message}", success = false)
            } catch (e: Exception) {
                showStatusMessage("Error: ${e.message}", success = false)
            } finally {
                try {
                    if (satocashClient != null && !waitingForRescan) {
                        satocashClient?.close()
                        Log.d(TAG, "NFC connection closed in finally block.")
                        satocashClient = null
                    }
                } catch (e: java.io.IOException) {
                    Log.e(TAG, "Error closing NFC connection: ${e.message}")
                }

                mainHandler.post {
                    nfcDialog?.takeIf { it.isShowing }?.dismiss()
                }
            }
        }.start()
    }

    private fun processImportWithSavedPin(tag: Tag) {
        val pin = savedPin
        if (pin.isNullOrEmpty()) {
            Log.e(TAG, "No saved PIN available for import")
            showStatusMessage("No saved PIN available", success = false)
            return
        }

        Thread {
            var tempClient: SatocashNfcClient? = null
            try {
                rescanDialog?.takeIf { it.isShowing }?.let { dialog ->
                    mainHandler.post { dialog.dismiss() }
                }

                mainHandler.post { showProcessingDialog() }

                tempClient = SatocashNfcClient(tag)
                tempClient.connect()
                Log.d(TAG, "Connected to NFC card for PIN import")

                satocashClient = tempClient
                satocashWallet = SatocashWallet(satocashClient!!)

                satocashClient!!.selectApplet(SatocashNfcClient.SATOCASH_AID)
                Log.d(TAG, "Satocash Applet found and selected!")

                satocashClient!!.initSecureChannel()
                Log.d(TAG, "Secure Channel Initialized!")

                Log.d(TAG, "Authenticating with saved PIN...")
                val authenticated = satocashWallet!!.authenticatePIN(pin).join()

                if (authenticated) {
                    Log.d(TAG, "PIN Verified! Card Ready.")

                    val token = pendingProofToken ?: ""
                    val importedCount = satocashWallet!!.importProofsFromToken(token).join()

                    waitingForRescan = false
                    savedPin = null

                    showStatusMessage("Success: Imported $importedCount proofs", success = true)

                    mainHandler.post {
                        pendingProofToken = ""
                        proofTokenEditText.setText("")
                    }
                } else {
                    val message = "PIN Verification Failed"
                    Log.e(TAG, message)
                    waitingForRescan = false
                    savedPin = null
                    showStatusMessage(message, success = false)
                }
            } catch (re: RuntimeException) {
                val reCause = re.cause
                val message = if (reCause is SatocashNfcClient.SatocashException) {
                    "PIN Verification Failed: ${reCause.message} (SW: 0x%04X)".format(reCause.sw)
                } else {
                    "Authentication Failed: ${re.message}"
                }
                Log.e(TAG, message)
                waitingForRescan = false
                savedPin = null
                showStatusMessage(message, success = false)
            } catch (e: Exception) {
                val message = "An unexpected error occurred: ${e.message}"
                Log.e(TAG, message)
                waitingForRescan = false
                savedPin = null
                showStatusMessage(message, success = false)
            } finally {
                try {
                    satocashClient?.let { client ->
                        client.close()
                        Log.d(TAG, "NFC connection closed.")
                        satocashClient = null
                    }
                } catch (e: java.io.IOException) {
                    Log.e(TAG, "Error closing NFC connection: ${e.message}")
                }

                mainHandler.post {
                    processingDialog?.takeIf { it.isShowing }?.dismiss()
                }
            }
        }.start()
    }

    companion object {
        private const val TAG = "TopUpActivity"
    }
}
