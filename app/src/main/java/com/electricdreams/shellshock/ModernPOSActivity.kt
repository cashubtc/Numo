package com.electricdreams.shellshock

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.electricdreams.shellshock.core.data.model.PaymentHistoryEntry
import com.electricdreams.shellshock.core.model.Item
import com.electricdreams.shellshock.core.util.CurrencyManager
import com.electricdreams.shellshock.core.util.ItemManager
import com.electricdreams.shellshock.core.util.MintManager
import com.electricdreams.shellshock.feature.history.PaymentsHistoryActivity
import com.electricdreams.shellshock.feature.history.TokenHistoryActivity
import com.electricdreams.shellshock.ndef.CashuPaymentHelper
import com.electricdreams.shellshock.ndef.NdefHostCardEmulationService
import com.electricdreams.shellshock.SatocashNfcClient
import com.electricdreams.shellshock.SatocashWallet
import com.electricdreams.shellshock.ui.components.BottomNavItem
import com.electricdreams.shellshock.ui.components.CashAppBottomBar
import com.electricdreams.shellshock.ui.screens.BalanceScreen
import com.electricdreams.shellshock.ui.screens.CatalogScreen
import com.electricdreams.shellshock.ui.screens.HistoryScreen
import com.electricdreams.shellshock.ui.screens.KeypadScreen
import com.electricdreams.shellshock.ui.screens.SettingsScreen
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
    private val selectedNavIndex = mutableStateOf(2) // Default to Cash (Keypad) which is index 2

    // Screen Data States
    private val historyState = mutableStateOf<List<PaymentHistoryEntry>>(emptyList())
    private val catalogItemsState = mutableStateOf<List<Item>>(emptyList())
    private val balanceState = mutableStateOf<Long?>(null)
    private val balanceStatusState = mutableStateOf("Tap your NFC card to check balance")

    private var currentInput = StringBuilder()
    private var nfcDialog: AlertDialog? = null
    private var nfcAdapter: NfcAdapter? = null
    private var bitcoinPriceWorker: com.electricdreams.shellshock.core.worker.BitcoinPriceWorker? = null
    private var itemManager: ItemManager? = null

    // Flag to indicate if we're in USD input mode
    private var isUsdInputMode = false

    private var requestedAmount: Long = 0
    private var isNightMode = false
    private var vibrator: android.os.Vibrator? = null
    
    // NFC Balance Check Client
    private var satocashClient: SatocashNfcClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Load theme preference
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        isNightMode = prefs.getBoolean(KEY_NIGHT_MODE, false)
        AppCompatDelegate.setDefaultNightMode(if (isNightMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)

        super.onCreate(savedInstanceState)

        setContent {
            CashAppTheme(darkTheme = isNightMode) {
                Scaffold(
                    bottomBar = {
                        CashAppBottomBar(
                            items = listOf(
                                BottomNavItem(Icons.Filled.Home, "Home"),
                                BottomNavItem(Icons.Filled.CreditCard, "Card"),
                                BottomNavItem(Icons.Filled.AttachMoney, "Cash"),
                                BottomNavItem(Icons.Filled.ShowChart, "Investing"),
                                BottomNavItem(Icons.Filled.AccountCircle, "Profile")
                            ),
                            selectedIndex = selectedNavIndex.value,
                            onItemSelected = { index -> 
                                selectedNavIndex.value = index
                                // Refresh data when switching tabs
                                when (index) {
                                    0 -> loadHistory()
                                    1 -> { /* Balance is updated via NFC */ }
                                    3 -> loadCatalog()
                                }
                            }
                        )
                    },
                    containerColor = Color.White
                ) { paddingValues ->
                    Box(modifier = Modifier.padding(bottom = paddingValues.calculateBottomPadding())) {
                        when (selectedNavIndex.value) {
                            0 -> HistoryScreen(
                                history = historyState.value,
                                onBackClick = null, // Top-level, no back
                                onClearHistoryClick = { /* Implement clear */ },
                                onCopyClick = { /* Implement copy */ },
                                onOpenClick = { /* Implement open */ },
                                onDeleteClick = { /* Implement delete */ }
                            )
                            1 -> BalanceScreen(
                                balance = balanceState.value,
                                statusMessage = balanceStatusState.value,
                                onBackClick = null // Top-level
                            )
                            2 -> KeypadScreen(
                                amount = amountState.value,
                                currencySymbol = currencySymbolState.value,
                                isUsdMode = isUsdModeState.value,
                                onKeyPress = { key -> onKeypadButtonClick(key) },
                                onDelete = { onDeleteClick() },
                                onToggleCurrency = { toggleInputMode() },
                                onRequestClick = { Toast.makeText(this@ModernPOSActivity, "Request feature coming soon", Toast.LENGTH_SHORT).show() },
                                onPayClick = { onSubmitClick() },
                                onQrClick = { Toast.makeText(this@ModernPOSActivity, "QR Scan coming soon", Toast.LENGTH_SHORT).show() },
                                onProfileClick = { selectedNavIndex.value = 4 } // Go to Profile tab
                            )
                            3 -> CatalogScreen(
                            items = catalogItemsState.value,
                            basketQuantities = emptyMap(), // TODO: Connect basket
                            basketTotalItems = 0,
                            basketTotalPrice = "$0.00",
                            onItemAdd = { /* TODO */ },
                            onItemRemove = { /* TODO */ },
                            onViewBasket = { /* TODO */ },
                            onCheckout = { /* TODO */ },
                            onBackClick = null // Top-level
                        )
                            4 -> SettingsScreen(
                            currentCurrency = "USD", // TODO: Load from settings
                            onCurrencySelected = { /* TODO */ },
                            mints = emptyList(), // TODO: Load from MintManager
                            onAddMint = { /* TODO */ },
                            onRemoveMint = { /* TODO */ },
                            onResetMints = { /* TODO */ },
                            itemsCount = catalogItemsState.value.size,
                            onImportItems = { /* TODO */ },
                            onManageItems = { /* TODO */ },
                            onClearItems = { /* TODO */ },
                            onBackClick = null, // Top-level
                            onSaveClick = { /* TODO */ }
                        )
                        }
                    }
                }
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
        
        itemManager = ItemManager.getInstance(this)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator

        // Ensure initial state
        updateDisplay()
        loadHistory()
        loadCatalog()

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
    
    private fun loadHistory() {
        // Load history from TokenHistoryActivity's logic (reused here)
        val tokenHistory = TokenHistoryActivity.getTokenHistory(this)
        val paymentHistory = tokenHistory.map { 
            PaymentHistoryEntry(it.token, it.amount, it.date) 
        }.toMutableList()
        paymentHistory.reverse()
        historyState.value = paymentHistory
    }
    
    private fun loadCatalog() {
        catalogItemsState.value = itemManager?.allItems ?: emptyList()
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

        val cancelButton = dialogView.findViewById<android.widget.Button>(R.id.nfc_cancel_button)
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
        nfcDialog!!.window?.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
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
        
        // Save to history
        TokenHistoryActivity.addToHistory(this, token, requestedAmount)
        loadHistory() // Refresh history

        currentInput.setLength(0)
        updateDisplay()
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
    
    // NFC Handling
    override fun onResume() {
        super.onResume()
        nfcAdapter?.let { adapter ->
            val pendingIntent = android.app.PendingIntent.getActivity(
                this, 0, Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                android.app.PendingIntent.FLAG_MUTABLE
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
            val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
            tag?.let { 
                // Check which tab is active
                if (selectedNavIndex.value == 1) {
                    // Balance Tab - Check Balance
                    checkNfcBalance(it)
                } else {
                    // Other tabs - Default behavior? Or maybe nothing?
                    // For now, only handle balance check on Balance tab.
                    // Payment is handled via Dialog which has its own HCE service, 
                    // but if we wanted to do reader mode payment, we'd do it here.
                }
            }
        }
    }
    
    private fun checkNfcBalance(tag: Tag) {
        balanceStatusState.value = "Reading card..."
        
        Thread {
            try {
                satocashClient = SatocashNfcClient(tag)
                satocashClient?.connect()
                satocashClient?.selectApplet(SatocashNfcClient.SATOCASH_AID)
                satocashClient?.initSecureChannel()

                val totalBalance = getCardBalance()
                
                runOnUiThread {
                    balanceState.value = totalBalance
                    balanceStatusState.value = "Balance check complete"
                }

            } catch (e: Exception) {
                runOnUiThread {
                    balanceStatusState.value = "Error: ${e.message}"
                }
            } finally {
                try {
                    satocashClient?.close()
                } catch (e: java.io.IOException) {
                    // Ignore
                }
            }
        }.start()
    }

    private fun getCardBalance(): Long {
        val client = satocashClient ?: return 0
        
        try {
            val status = client.status
            val nbProofsUnspent = (status["nb_proofs_unspent"] as? Number)?.toInt() ?: 0
            val nbProofsSpent = (status["nb_proofs_spent"] as? Number)?.toInt() ?: 0
            val totalProofs = nbProofsUnspent + nbProofsSpent

            if (totalProofs == 0) return 0

            val proofStates = client.getProofInfo(
                SatocashNfcClient.Unit.SAT,
                SatocashNfcClient.ProofInfoType.METADATA_STATE,
                0,
                totalProofs
            )

            if (proofStates.isEmpty()) return 0

            val amounts = client.getProofInfo(
                SatocashNfcClient.Unit.SAT,
                SatocashNfcClient.ProofInfoType.METADATA_AMOUNT_EXPONENT,
                0,
                totalProofs
            )

            var totalBalance = 0L
            for (i in proofStates.indices) {
                if (proofStates[i] == 1) { // 1 = unspent
                    val amountExponent = amounts[i]
                    totalBalance += Math.pow(2.0, amountExponent.toDouble()).toLong()
                }
            }
            return totalBalance

        } catch (e: Exception) {
            throw e
        }
    }
}
