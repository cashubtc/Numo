package com.example.shellshock

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import android.app.AlertDialog
import android.text.InputType
import android.widget.LinearLayout
import kotlinx.coroutines.suspendCancellableCoroutine
import com.cashujdk.nut00.Proof
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView
import android.view.MenuItem
import androidx.appcompat.widget.Toolbar
import androidx.appcompat.app.AppCompatActivity
import android.widget.Toast

class MainActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {
    private val TAG = "MainActivity"
    private lateinit var textView: TextView
    private lateinit var nfcScanHint: TextView
    private lateinit var keypadLayout: LinearLayout
    private lateinit var amountDisplay: TextView
    private lateinit var tokenDisplay: TextView
    private lateinit var copyTokenButton: Button
    private lateinit var requestPaymentButton: Button
    private var nfcAdapter: NfcAdapter? = null
    
    private fun copyTokenToClipboard(token: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Cashu Token", token)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Token copied to clipboard", Toast.LENGTH_SHORT).show()
    }
    
    private fun log(message: String) {
        Log.d(TAG, message)
        lifecycleScope.launch(Dispatchers.Main) {
            textView.append("\n$message")
            // Auto-scroll to bottom
            val scrollAmount = textView.layout?.getLineTop(textView.lineCount) ?: 0
            textView.scrollTo(0, scrollAmount)
        }
    }

    private var requestedAmount: Long = 0
    private var satocashClient: SatocashNfcClient? = null
    private var satocashWallet: SatocashWallet? = null

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView
    private lateinit var toggle: ActionBarDrawerToggle

    companion object {
        @JvmStatic
        var requestAmount: Long = 0
    }

    private fun updateUIState(showToken: Boolean = false, token: String? = null) {
        tokenDisplay.visibility = if (showToken) View.VISIBLE else View.GONE
        token?.let { tokenDisplay.text = it }

        if (requestAmount > 0) {
            // If we have a requested amount, show NFC scanning UI
            keypadLayout.visibility = View.GONE
            nfcScanHint.visibility = View.VISIBLE
            textView.visibility = View.VISIBLE
            textView.text = "Please tap your card to start the payment of $requestAmount SAT"
        } else {
            // Otherwise show the keypad (and token if present)
            keypadLayout.visibility = View.VISIBLE
            nfcScanHint.visibility = View.GONE
            textView.visibility = View.GONE
            textView.text = ""
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Log.d(TAG, "OnCreate was called")

        // --- Toolbar and Navigation Drawer Setup ---
        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        drawerLayout = findViewById(R.id.drawer_layout)
        navigationView = findViewById(R.id.nav_view)

        toggle = ActionBarDrawerToggle(
            this,
            drawerLayout,
            toolbar,
            R.string.navigation_drawer_open,
            R.string.navigation_drawer_close
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        navigationView.setNavigationItemSelectedListener(this)

        // Find UI components
        textView = findViewById(R.id.textView)
        nfcScanHint = findViewById(R.id.nfc_scan_hint)
        keypadLayout = findViewById(R.id.keypad_layout)
        amountDisplay = findViewById(R.id.amount_display)
        tokenDisplay = findViewById(R.id.token_display)
        copyTokenButton = findViewById(R.id.copy_token_button)
        requestPaymentButton = findViewById(R.id.request_payment_button)

        // Set up copy button click listener
        copyTokenButton.setOnClickListener {
            tokenDisplay.text.toString().let { token ->
                if (token.isNotEmpty() && !token.startsWith("Error:")) {
                    copyTokenToClipboard(token)
                }
            }
        }

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            textView.text = "NFC is not available on this device."
            nfcScanHint.visibility = View.GONE
            keypadLayout.visibility = View.GONE
            textView.visibility = View.VISIBLE
            return
        }
        updateUIState()

        setupKeypadListeners()

        requestPaymentButton.setOnClickListener {
            if (requestedAmount > 0) {
                requestAmount = requestedAmount  // Set the static variable
                updateUIState()
            } else {
                Toast.makeText(this, "Please enter an amount greater than 0", Toast.LENGTH_SHORT).show()
            }
        }

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
        updateUIState()
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

    private fun clearPaymentUI() {
        requestAmount = 0
        updateUIState(showToken = false)
    }

    private fun handlePaymentSuccess(token: String) {
        requestAmount = 0  // Reset the payment amount first
        requestedAmount = 0  // Also reset the input amount
        lifecycleScope.launch(Dispatchers.Main) {
            withContext(Dispatchers.Main) {
                tokenDisplay.visibility = View.VISIBLE
                tokenDisplay.text = token
                copyTokenButton.visibility = View.VISIBLE
                keypadLayout.visibility = View.VISIBLE
                nfcScanHint.visibility = View.GONE
                textView.visibility = View.GONE
                amountDisplay.text = "0"
                Toast.makeText(this@MainActivity, "Payment successful!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handlePaymentError(message: String) {
        requestAmount = 0  // Reset the payment amount first
        requestedAmount = 0  // Also reset the input amount
        lifecycleScope.launch(Dispatchers.Main) {
            withContext(Dispatchers.Main) {
                tokenDisplay.visibility = View.VISIBLE
                tokenDisplay.text = "Error: $message"
                copyTokenButton.visibility = View.GONE
                keypadLayout.visibility = View.VISIBLE
                nfcScanHint.visibility = View.GONE
                textView.visibility = View.GONE
                amountDisplay.text = "0"
            }
        }
    }

    @SuppressLint("SetTextI19n")
    private fun handleNfcIntent(intent: Intent) {
        val tag: Tag? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }
        if (tag != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    satocashClient = SatocashNfcClient(tag)
                    satocashClient?.connect()
                    log("Connected to NFC card")
                    
                    satocashWallet = SatocashWallet(satocashClient!!)
                    log("Created Satocash wallet instance")

                    satocashClient?.selectApplet(SatocashNfcClient.SATOCASH_AID)
                    log("Satocash Applet found and selected!")

                    satocashClient?.initSecureChannel()
                    log("Secure Channel Initialized!")

                    val pin = withContext(Dispatchers.Main) {
                        showPinInputDialog()
                    }

                    if (pin != null) {
                        try {
                            satocashWallet?.authenticatePIN(pin)?.join()
                            log("PIN Verified! Card Ready.")

                            // If there's a requested amount, start the payment automatically
                            if (requestAmount > 0) {
                                try {
                                    log("Starting payment for $requestAmount SAT...")
                                    val token = satocashWallet!!.getPayment(requestAmount, "SAT").join()
                                    log("Payment successful! Token received.")
                                    
                                    withContext(Dispatchers.Main) {
                                        handlePaymentSuccess(token)
                                    }
                                    
                                } catch (e: Exception) {
                                    log("Payment failed: ${e.message}")
                                    withContext(Dispatchers.Main) {
                                        handlePaymentError(e.message ?: "Unknown error occurred during payment")
                                    }
                                }
                            }

                        } catch (e: RuntimeException) {
                            val cause = e.cause
                            if (cause is SatocashNfcClient.SatocashException) {
                                val message = "PIN Verification Failed: ${cause.message} (SW: ${String.format("0x%04X", cause.sw)})"
                                log(message)
                                withContext(Dispatchers.Main) {
                                    handlePaymentError(message)
                                }
                            } else {
                                val message = "Authentication Failed: ${e.message}"
                                log(message)
                                withContext(Dispatchers.Main) {
                                    handlePaymentError(message)
                                }
                            }
                        }
                    } else {
                        log("PIN entry cancelled.")
                        withContext(Dispatchers.Main) {
                            handlePaymentError("PIN entry cancelled")
                        }
                    }

                } catch (e: IOException) {
                    val message = "NFC Communication Error: ${e.message}"
                    log(message)
                    withContext(Dispatchers.Main) {
                        handlePaymentError(message)
                    }
                } catch (e: SatocashNfcClient.SatocashException) {
                    val message = "Satocash Card Error: ${e.message} (SW: ${String.format("0x%04X", e.sw)})"
                    log(message)
                    withContext(Dispatchers.Main) {
                        handlePaymentError(message)
                    }
                } catch (e: Exception) {
                    val message = "An unexpected error occurred: ${e.message}"
                    log(message)
                    withContext(Dispatchers.Main) {
                        handlePaymentError(message)
                    }
                } finally {
                    try {
                        satocashClient?.close()
                        log("NFC connection closed.")
                    } catch (e: IOException) {
                        log("Error closing NFC connection: ${e.message}")
                    }
                    // Let the success/error handlers manage the UI state
                }
            }
        }
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.nav_pos -> {
                Toast.makeText(this, "Already at Point of Sale", Toast.LENGTH_SHORT).show()
                updateUIState()
            }
            R.id.nav_request_payment -> {
                Toast.makeText(this, "Request Payment selected", Toast.LENGTH_SHORT).show()
                updateUIState()
            }
            R.id.nav_import_proof -> {
                val intent = Intent(this, ImportProofActivity::class.java)
                startActivity(intent)
            }
        }
        drawerLayout.closeDrawers()
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (toggle.onOptionsItemSelected(item)) {
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(navigationView)) {
            drawerLayout.closeDrawer(navigationView)
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun showPinInputDialog(): String? = withContext(Dispatchers.Main) {
        return@withContext suspendCancellableCoroutine { continuation ->
            val builder = AlertDialog.Builder(this@MainActivity)
            builder.setTitle("Enter PIN")

            val input = EditText(this@MainActivity)
            input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            input.hint = "PIN"

            val layout = LinearLayout(this@MainActivity)
            layout.orientation = LinearLayout.VERTICAL
            layout.setPadding(50, 20, 50, 20)
            layout.addView(input)

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
                arrayOf("", "0", "DEL")
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

    private fun ByteArray.toHexString(): String =
        joinToString(separator = "") { eachByte -> "%02x".format(eachByte) }
}
