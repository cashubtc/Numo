package com.electricdreams.shellshock

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.nfc.NfcAdapter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.electricdreams.shellshock.core.util.CurrencyManager
import com.electricdreams.shellshock.core.util.MintManager
import com.electricdreams.shellshock.feature.history.PaymentsHistoryActivity
import com.electricdreams.shellshock.feature.settings.SettingsActivity
import com.electricdreams.shellshock.ndef.CashuPaymentHelper
import com.electricdreams.shellshock.ndef.NdefHostCardEmulationService
import com.electricdreams.shellshock.ui.components.BottomNavItem
import com.electricdreams.shellshock.ui.components.CashAppBottomBar
import com.electricdreams.shellshock.ui.screens.KeypadScreen
import com.electricdreams.shellshock.ui.theme.CashAppTheme
import java.text.NumberFormat
import java.util.Locale

class ModernPOSActivity : AppCompatActivity(), SatocashWallet.OperationFeedback {

    companion object {
        private const val TAG = "ModernPOSActivity"
        private const val PREFS_NAME = "ShellshockPrefs"
        private const val KEY_NIGHT_MODE = "nightMode"
    }

    // Compose State
    private val amountState = mutableStateOf("")
    private val currencySymbolState = mutableStateOf("$")
    private val isUsdModeState = mutableStateOf(false)
    private val selectedNavIndex = mutableStateOf(0)

    private var currentInput = StringBuilder()
    private var nfcDialog: AlertDialog? = null
    private var nfcAdapter: NfcAdapter? = null
    private var bitcoinPriceWorker: com.electricdreams.shellshock.core.worker.BitcoinPriceWorker? = null

    // Flag to indicate if we're in USD input mode
    private var isUsdInputMode = false

    private var requestedAmount: Long = 0
    private var isNightMode = false
    private var vibrator: android.os.Vibrator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Load theme preference
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        isNightMode = prefs.getBoolean(KEY_NIGHT_MODE, false)
        AppCompatDelegate.setDefaultNightMode(if (isNightMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)

        super.onCreate(savedInstanceState)

        setContent {
            CashAppTheme(darkTheme = isNightMode) {
                KeypadScreen(
                    amount = amountState.value,
                    currencySymbol = currencySymbolState.value,
                    isUsdMode = isUsdModeState.value,
                    onKeyPress = { key -> onKeypadButtonClick(key) },
                    onDelete = { onDeleteClick() },
                    onToggleCurrency = { toggleInputMode() },
                    onRequestClick = { Toast.makeText(this, "Request feature coming soon", Toast.LENGTH_SHORT).show() },
                    onPayClick = { onSubmitClick() },
                    onQrClick = { Toast.makeText(this, "QR Scan coming soon", Toast.LENGTH_SHORT).show() },
                    onProfileClick = { startActivity(Intent(this, SettingsActivity::class.java)) },
                    bottomBar = {
                        CashAppBottomBar(
                            items = listOf(
                                BottomNavItem(Icons.Filled.Home, "Home"),
                                BottomNavItem(Icons.Filled.History, "Activity"),
                                BottomNavItem(Icons.Filled.List, "Catalog"),
                                BottomNavItem(Icons.Filled.Settings, "Settings")
                            ),
                            selectedIndex = selectedNavIndex.value,
                            onItemSelected = { index -> onBottomNavClick(index) }
                        )
                    }
                )
            }
        }

        // Initialize logic
        initLogic()
    }

    private fun initLogic() {
        // Check if we have a payment amount from intent (basket checkout)
        val intent = intent
        val paymentAmount = intent.getLongExtra("EXTRA_PAYMENT_AMOUNT", 0)

        // Initialize bitcoin price worker
        bitcoinPriceWorker = com.electricdreams.shellshock.core.worker.BitcoinPriceWorker.getInstance(this)
        bitcoinPriceWorker?.setPriceUpdateListener { price ->
            if (currentInput.isNotEmpty()) {
                updateDisplay()
            }
        }
        bitcoinPriceWorker?.start()

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator

        // Ensure initial state
        updateDisplay()

        // Check if we need to set up an automatic payment from basket checkout
        if (paymentAmount > 0) {
            currentInput = StringBuilder(paymentAmount.toString())
            requestedAmount = paymentAmount
            updateDisplay()

            Handler(Looper.getMainLooper()).postDelayed({
                showPaymentMethodDialog(paymentAmount)
            }, 500)
        }
    }

    private fun onBottomNavClick(index: Int) {
        selectedNavIndex.value = index
        when (index) {
            0 -> { /* Home - do nothing */ }
            1 -> startActivity(Intent(this, PaymentsHistoryActivity::class.java))
            2 -> startActivity(Intent(this, com.electricdreams.shellshock.feature.items.ItemSelectionActivity::class.java))
            3 -> startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun toggleInputMode() {
        val inputStr = currentInput.toString()
        var satsValue: Long = 0
        var fiatValue = 0.0

        if (isUsdInputMode) {
            // Currently in fiat mode, calculate sats
            if (inputStr.isNotEmpty()) {
                try {
                    val cents = inputStr.toLong()
                    fiatValue = cents / 100.0

                    if (bitcoinPriceWorker != null && bitcoinPriceWorker!!.currentPrice > 0) {
                        val btcAmount = fiatValue / bitcoinPriceWorker!!.currentPrice
                        satsValue = (btcAmount * 100000000).toLong()
                    }
                } catch (e: NumberFormatException) {
                    satsValue = 0
                    fiatValue = 0.0
                }
            }
        } else {
            // Currently in sats mode, calculate fiat
            satsValue = if (inputStr.isEmpty()) 0 else inputStr.toLong()
            if (bitcoinPriceWorker != null) {
                fiatValue = bitcoinPriceWorker!!.satoshisToFiat(satsValue)
            }
        }

        isUsdInputMode = !isUsdInputMode
        isUsdModeState.value = isUsdInputMode

        currentInput.setLength(0)

        if (isUsdInputMode) {
            if (fiatValue > 0) {
                val cents = (fiatValue * 100).toLong()
                currentInput.append(cents.toString())
            }
        } else {
            if (satsValue > 0) {
                currentInput.append(satsValue.toString())
            }
        }

        updateDisplay()
    }

    private fun onKeypadButtonClick(label: String) {
        vibrateKeypad()

        if (isUsdInputMode) {
            if (currentInput.length < 7) {
                currentInput.append(label)
            }
        } else {
            if (currentInput.length < 9) {
                currentInput.append(label)
            }
        }
        updateDisplay()
    }

    private fun onDeleteClick() {
        vibrateKeypad()
        if (currentInput.isNotEmpty()) {
            currentInput.setLength(currentInput.length - 1)
            updateDisplay()
        }
    }

    private fun onSubmitClick() {
        val amount = currentInput.toString()
        if (amount.isNotEmpty() && requestedAmount > 0) {
            showPaymentMethodDialog(requestedAmount)
        } else {
            Toast.makeText(this, "Please enter an amount", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateDisplay() {
        val inputStr = currentInput.toString()
        var satsValue: Long = 0

        if (isUsdInputMode) {
            if (inputStr.isNotEmpty()) {
                try {
                    val cents = inputStr.toLong()
                    val fiatValue = cents / 100.0

                    if (bitcoinPriceWorker != null && bitcoinPriceWorker!!.currentPrice > 0) {
                        val btcAmount = fiatValue / bitcoinPriceWorker!!.currentPrice
                        satsValue = (btcAmount * 100000000).toLong()
                    }

                    val currencyManager = CurrencyManager.getInstance(this)
                    val symbol = currencyManager.currentSymbol
                    currencySymbolState.value = symbol

                    val wholePart = (cents / 100).toString()
                    val centsPart = String.format("%02d", cents % 100)
                    amountState.value = "$wholePart.$centsPart"

                } catch (e: NumberFormatException) {
                    amountState.value = "0.00"
                    satsValue = 0
                }
            } else {
                amountState.value = "0.00"
                satsValue = 0
            }
        } else {
            satsValue = if (inputStr.isEmpty()) 0 else inputStr.toLong()
            currencySymbolState.value = "₿" // Or empty if we want just numbers

            if (inputStr.isEmpty()) {
                amountState.value = "0"
            } else {
                amountState.value = NumberFormat.getNumberInstance(Locale.US).format(satsValue)
            }
        }

        requestedAmount = satsValue
    }

    private fun vibrateKeypad() {
        vibrator?.vibrate(20)
    }

    private fun showPaymentMethodDialog(amount: Long) {
        proceedWithUnifiedPayment(amount)
    }

    private fun proceedWithUnifiedPayment(amount: Long) {
        requestedAmount = amount
        val ndefAvailable = NdefHostCardEmulationService.isHceAvailable(this)
        var paymentRequestLocal: String? = null

        val mintManager = MintManager.getInstance(this)
        val allowedMints = mintManager.allowedMints

        if (ndefAvailable) {
            paymentRequestLocal = CashuPaymentHelper.createPaymentRequest(
                amount,
                "Payment of $amount sats",
                allowedMints
            )

            if (paymentRequestLocal != null) {
                val serviceIntent = Intent(this, NdefHostCardEmulationService::class.java)
                startService(serviceIntent)
            }
        }
        val finalPaymentRequest = paymentRequestLocal

        val builder = AlertDialog.Builder(this, R.style.Theme_Shellshock)
        val inflater = this.layoutInflater
        val dialogView = inflater.inflate(R.layout.dialog_nfc_modern_simplified, null)
        builder.setView(dialogView)

        val cancelButton = dialogView.findViewById<Button>(R.id.nfc_cancel_button)
        cancelButton?.setOnClickListener {
            if (nfcDialog != null && nfcDialog!!.isShowing) {
                nfcDialog!!.dismiss()
            }
            if (ndefAvailable) {
                resetHceService()
            }
            Toast.makeText(this, "Payment canceled", Toast.LENGTH_SHORT).show()
        }

        builder.setCancelable(true)
        builder.setOnCancelListener {
            if (ndefAvailable) {
                stopHceService()
            }
        }

        if (ndefAvailable && finalPaymentRequest != null) {
            Handler(Looper.getMainLooper()).postDelayed({
                val hceService = NdefHostCardEmulationService.getInstance()
                if (hceService != null) {
                    hceService.setPaymentRequest(finalPaymentRequest, amount)
                    hceService.setPaymentCallback(object : NdefHostCardEmulationService.CashuPaymentCallback {
                        override fun onCashuTokenReceived(token: String) {
                            runOnUiThread {
                                try {
                                    handlePaymentSuccess(token)
                                } catch (e: Exception) {
                                    handlePaymentError("Error processing NDEF payment: ${e.message}")
                                }
                            }
                        }

                        override fun onCashuPaymentError(errorMessage: String) {
                            runOnUiThread {
                                handlePaymentError("NDEF Payment failed: $errorMessage")
                            }
                        }
                    })
                }
            }, 1000)
        }

        nfcDialog = builder.create()
        nfcDialog!!.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        nfcDialog!!.show()
    }

    private fun handlePaymentSuccess(token: String) {
        if (nfcDialog != null && nfcDialog!!.isShowing) {
            nfcDialog!!.dismiss()
        }
        stopHceService()

        // Vibrate success
        vibrator?.vibrate(longArrayOf(0, 50, 100, 50), -1)

        Toast.makeText(this, "Payment Successful!", Toast.LENGTH_LONG).show()
        currentInput.setLength(0)
        updateDisplay()

        // Navigate to history or show receipt?
        // For now just stay on keypad
    }

    private fun handlePaymentError(error: String) {
        if (nfcDialog != null && nfcDialog!!.isShowing) {
            nfcDialog!!.dismiss()
        }
        stopHceService()

        // Vibrate error
        vibrator?.vibrate(longArrayOf(0, 100), -1)

        Toast.makeText(this, error, Toast.LENGTH_LONG).show()
    }

    private fun stopHceService() {
        val serviceIntent = Intent(this, NdefHostCardEmulationService::class.java)
        stopService(serviceIntent)
    }

    private fun resetHceService() {
        val hceService = NdefHostCardEmulationService.getInstance()
        if (hceService != null) {
            hceService.clearPaymentRequest()
            hceService.setPaymentCallback(null)
        }
    }

    override fun onOperationSuccess() {
        runOnUiThread { Toast.makeText(this, "Operation Successful", Toast.LENGTH_SHORT).show() }
    }

    override fun onOperationError() {
        runOnUiThread { Toast.makeText(this, "Operation Failed", Toast.LENGTH_SHORT).show() }
    }
}
