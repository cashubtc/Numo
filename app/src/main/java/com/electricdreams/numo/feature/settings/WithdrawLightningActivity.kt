package com.electricdreams.numo.feature.settings

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.electricdreams.numo.R
import com.electricdreams.numo.core.cashu.CashuWalletManager
import com.electricdreams.numo.core.dev.WalletLogger
import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.core.worker.BitcoinPriceWorker
import com.electricdreams.numo.core.util.BalanceRefreshBroadcast
import com.electricdreams.numo.core.util.LightningAddressManager
import com.electricdreams.numo.core.util.MintManager
import com.electricdreams.numo.feature.scanner.QRScannerActivity
import com.electricdreams.numo.feature.withdraw.WithdrawInputParser
import com.electricdreams.numo.ui.util.QrCodeGenerator
import com.electricdreams.numo.ui.util.shake
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cashudevkit.CurrencyUnit
import org.cashudevkit.MintUrl
import org.cashudevkit.SendKind
import org.cashudevkit.SendOptions
import org.cashudevkit.SplitTarget
import org.cashudevkit.P2pkLockedProofSendMode
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Activity for withdrawing balance from a mint via Lightning or a Cashu token.
 *
 * The Lightning tab uses one smart destination input that auto-detects a BOLT11
 * invoice vs a lightning address (with paste and scan aids). Invoices carry
 * their own amount and go straight to the melt-quote confirmation; addresses
 * continue to a keypad amount screen first. The lightning address is shared
 * with the auto-withdraw feature.
 */
class WithdrawLightningActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "WithdrawLightning"
        private const val FEE_BUFFER_PERCENT = com.electricdreams.numo.feature.withdraw.WithdrawConstants.FEE_BUFFER_PERCENT
    }

    private lateinit var mintUrl: String
    private var balance: Long = 0
    private lateinit var mintManager: MintManager
    private lateinit var lightningAddressManager: LightningAddressManager

    // Views
    private lateinit var topBar: com.electricdreams.numo.ui.components.NumoTopBar
    private lateinit var balanceCard: MaterialCardView
    private lateinit var mintNameText: TextView
    private lateinit var balanceText: TextView
    private lateinit var fiatBalanceText: TextView
    private lateinit var destinationInput: EditText
    private lateinit var destinationStatus: TextView
    private lateinit var suggestionChip: TextView
    private lateinit var pasteButton: Button
    private lateinit var scanButton: Button
    private lateinit var destinationContinueButton: Button
    private lateinit var loadingOverlay: FrameLayout

    private var parsedDestination: WithdrawInputParser.Result = WithdrawInputParser.Result.Invalid

    // Tabs
    private lateinit var tabsContainer: View
    private lateinit var tabLightning: TextView
    private lateinit var tabCashu: TextView
    private lateinit var lightningOptionsContainer: View
    private lateinit var cashuTokenOptionsContainer: View

    // Cashu Token UI
    private lateinit var cashuAmountInput: EditText
    private lateinit var createTokenButton: Button
    private lateinit var tokenResultCard: View
    private lateinit var tokenQrCode: ImageView
    private lateinit var tokenText: TextView
    private lateinit var copyTokenButton: Button

    // QR Scanner Launcher
    private val scanLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val qrValue = result.data?.getStringExtra(QRScannerActivity.EXTRA_QR_VALUE)
            if (!qrValue.isNullOrBlank()) {
                setDestinationFromExternal(qrValue, R.string.withdraw_destination_invalid)
            }
        }
    }

    // Balance refresh receiver
    private val balanceRefreshReceiver: BroadcastReceiver = BalanceRefreshBroadcast.createReceiver { reason ->
        Log.d(TAG, "Balance refresh broadcast received: $reason")
        refreshBalance()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_withdraw_lightning)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, insets.top, 0, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        mintUrl = intent.getStringExtra("mint_url") ?: ""
        balance = intent.getLongExtra("balance", 0)
        mintManager = MintManager.getInstance(this)
        lightningAddressManager = LightningAddressManager.getInstance(this)

        if (mintUrl.isEmpty()) {
            Toast.makeText(
                this,
                getString(R.string.withdraw_lightning_error_invalid_mint_url),
                Toast.LENGTH_SHORT
            ).show()
            finish()
            return
        }

        initViews()
        setupListeners()
        displayMintInfo()
        prefillFields()
    }

    private fun initViews() {
        topBar = findViewById(R.id.top_bar)
        balanceCard = findViewById(R.id.balance_card)
        mintNameText = findViewById(R.id.mint_name_text)
        balanceText = findViewById(R.id.balance_text)
        fiatBalanceText = findViewById(R.id.fiat_balance_text)
        destinationInput = findViewById(R.id.destination_input)
        destinationStatus = findViewById(R.id.destination_status)
        suggestionChip = findViewById(R.id.suggestion_chip)
        pasteButton = findViewById(R.id.paste_button)
        scanButton = findViewById(R.id.scan_button)
        destinationContinueButton = findViewById(R.id.destination_continue_button)
        loadingOverlay = findViewById(R.id.loading_overlay)

        // Initialize new views
        tabsContainer = findViewById(R.id.tabs_container)
        tabLightning = findViewById(R.id.tab_lightning)
        tabCashu = findViewById(R.id.tab_cashu)
        lightningOptionsContainer = findViewById(R.id.lightning_options_container)
        cashuTokenOptionsContainer = findViewById(R.id.cashu_token_options_container)
        
        cashuAmountInput = findViewById(R.id.cashu_amount_input)
        createTokenButton = findViewById(R.id.create_token_button)
        tokenResultCard = findViewById(R.id.token_result_card)
        tokenQrCode = findViewById(R.id.token_qr_code)
        tokenText = findViewById(R.id.token_text)
        copyTokenButton = findViewById(R.id.copy_token_button)
    }

    private fun setupListeners() {
        topBar.onNavClick { finish() }

        // Smart destination input: parse on every change
        destinationInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                onDestinationChanged(s?.toString() ?: "")
            }
        })

        pasteButton.setOnClickListener { pasteDestinationFromClipboard() }

        scanButton.setOnClickListener {
            val intent = Intent(this@WithdrawLightningActivity, QRScannerActivity::class.java)
            intent.putExtra(QRScannerActivity.EXTRA_TITLE, getString(R.string.withdraw_scan_qr))
            scanLauncher.launch(intent)
        }

        destinationContinueButton.setOnClickListener {
            when (val destination = parsedDestination) {
                is WithdrawInputParser.Result.Bolt11 -> processInvoice(destination.invoice)
                is WithdrawInputParser.Result.LightningAddress -> openAmountScreen(destination.address)
                WithdrawInputParser.Result.Invalid -> {
                    destinationInput.shake()
                    showDestinationError(getString(R.string.withdraw_destination_invalid))
                }
            }
        }

        // Tab Listeners
        tabLightning.setOnClickListener {
            switchTab(isLightning = true)
        }
        tabCashu.setOnClickListener {
            switchTab(isLightning = false)
        }

        // Cashu Token Logic
        createTokenButton.setOnClickListener {
            val amountStr = cashuAmountInput.text.toString()
            val amount = amountStr.toLongOrNull() ?: 0L
            if (amount > 0) {
                createCashuToken(amount)
            } else {
                Toast.makeText(this, getString(R.string.withdraw_lightning_error_enter_valid_amount), Toast.LENGTH_SHORT).show()
            }
        }

        // Watch cashu amount input to enable button
        cashuAmountInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val amount = s?.toString()?.toLongOrNull() ?: 0L
                createTokenButton.isEnabled = amount > 0
            }
        })

        copyTokenButton.setOnClickListener {
            val token = tokenText.text.toString()
            if (token.isNotEmpty()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Cashu Token", token)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, getString(R.string.withdraw_cashu_copied), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun switchTab(isLightning: Boolean) {
        if (isLightning) {
            tabLightning.setBackgroundResource(R.drawable.bg_button_primary_green)
            tabLightning.setTextColor(getColor(R.color.color_bg_white))

            tabCashu.setBackgroundResource(android.R.color.transparent)
            tabCashu.setTextColor(getColor(R.color.color_text_secondary))

            lightningOptionsContainer.visibility = View.VISIBLE
            cashuTokenOptionsContainer.visibility = View.GONE
        } else {
            tabCashu.setBackgroundResource(R.drawable.bg_button_primary_green)
            tabCashu.setTextColor(getColor(R.color.color_bg_white))

            tabLightning.setBackgroundResource(android.R.color.transparent)
            tabLightning.setTextColor(getColor(R.color.color_text_secondary))

            lightningOptionsContainer.visibility = View.GONE
            cashuTokenOptionsContainer.visibility = View.VISIBLE
        }
    }

    private fun createCashuToken(amountSats: Long) {
        if (amountSats > balance) {
            Toast.makeText(this, R.string.withdraw_toast_insufficient_balance, Toast.LENGTH_SHORT).show()
            return
        }

        setLoading(true)
        
        lifecycleScope.launch {
            try {
                val walletRepo = CashuWalletManager.getWallet()
                if (walletRepo == null) {
                    throw Exception("Wallet not initialized")
                }

                // Get single mint wallet
                val unit = com.electricdreams.numo.core.util.MintManager.getInstance(this@WithdrawLightningActivity).getPreferredUnit()
                val mintWallet = walletRepo.getWallet(MintUrl(mintUrl), com.electricdreams.numo.core.cashu.CashuWalletManager.getCurrencyUnit(unit))
                    ?: throw Exception("Failed to get wallet for mint: $mintUrl")

                // Prepare Send
                val amount = org.cashudevkit.Amount(amountSats.toULong())
                val options = SendOptions(
                    memo = null,
                    conditions = null,
                    amountSplitTarget = SplitTarget.None,
                    sendKind = SendKind.OnlineTolerance(org.cashudevkit.Amount(0UL)),
                    includeFee = true,
                    maxProofs = null,
                    metadata = emptyMap(),
                    useP2bk = false,
                    p2pkSigningKeys = emptyList(),
                    p2pkLockedProofSendMode = P2pkLockedProofSendMode.SWAP,
                )

                val preparedSend = withContext(Dispatchers.IO) {
                    mintWallet.prepareSend(amount, options)
                }

                // Confirm and get token
                val token = withContext(Dispatchers.IO) {
                    preparedSend.confirm(null)
                }
                WalletLogger.log("OUT", amountSats, mintUrl, "Token created (melt)")
                
                val tokenString = token.encode()

                // Save to withdrawal history
                val autoWithdrawManager = com.electricdreams.numo.feature.autowithdraw.AutoWithdrawManager.getInstance(this@WithdrawLightningActivity)
                autoWithdrawManager.addManualWithdrawalEntry(
                    mintUrl = mintUrl,
                    amountSats = amountSats,
                    feeSats = 0L,
                    destination = getString(R.string.withdraw_cashu_result_title),
                    destinationType = "manual_token",
                    status = com.electricdreams.numo.feature.autowithdraw.WithdrawHistoryEntry.STATUS_COMPLETED,
                    quoteId = null,
                    errorMessage = null,
                    token = tokenString
                )

                // Resolve theme colors
                val currentNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                val isDarkTheme = currentNightMode == Configuration.UI_MODE_NIGHT_YES
                val qrForeground = if (isDarkTheme) Color.WHITE else Color.BLACK
                val qrBackground = Color.TRANSPARENT

                // Generate QR - handle potential size overflow gracefully
                var qrBitmap: Bitmap? = null
                try {
                    qrBitmap = QrCodeGenerator.generate(tokenString, 512, qrForeground, qrBackground)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to generate QR code for token (likely too large)", e)
                }

                withContext(Dispatchers.Main) {
                    if (qrBitmap != null) {
                        tokenQrCode.setImageBitmap(qrBitmap)
                        tokenQrCode.visibility = View.VISIBLE
                    } else {
                        tokenQrCode.visibility = View.GONE
                    }

                    tokenText.text = tokenString
                    tokenResultCard.visibility = View.VISIBLE
                    
                    // Hide input to focus on result
                    cashuAmountInput.isEnabled = false
                    createTokenButton.isEnabled = false
                    createTokenButton.alpha = 0.5f
                    createTokenButton.text = getString(R.string.withdraw_cashu_token_created)
                    
                    setLoading(false)
                    refreshBalance()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error creating token", e)
                withContext(Dispatchers.Main) {
                    setLoading(false)
                    Toast.makeText(
                        this@WithdrawLightningActivity,
                        com.electricdreams.numo.feature.withdraw.WithdrawErrorMapper.resolve(this@WithdrawLightningActivity, e.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun displayMintInfo() {
        val displayName = mintManager.getMintDisplayName(mintUrl)
        mintNameText.text = displayName

        val balanceAmount = Amount(balance, Amount.Currency.BTC)
        balanceText.text = balanceAmount.toString()
        updateFiatDisplay(balance)
    }

    private fun updateFiatDisplay(sats: Long) {
        val priceWorker = BitcoinPriceWorker.getInstance(this)
        val fiatAmount = priceWorker.satoshisToFiat(sats)
        if (fiatAmount > 0) {
            fiatBalanceText.text = priceWorker.formatFiatAmount(fiatAmount)
            fiatBalanceText.visibility = android.view.View.VISIBLE
        } else {
            fiatBalanceText.visibility = android.view.View.GONE
        }
    }

    private fun prefillFields() {
        // Offer the shared auto-withdraw lightning address as a one-tap
        // suggestion, but don't prefill — the user may want to paste an invoice.
        val savedAddress = lightningAddressManager.getLightningAddress()
        if (savedAddress.isNotEmpty()) {
            suggestionChip.text = savedAddress
            suggestionChip.visibility = View.VISIBLE
            suggestionChip.setOnClickListener {
                destinationInput.setText(savedAddress)
                destinationInput.setSelection(destinationInput.text?.length ?: 0)
            }
        }
    }

    private fun parseDestination(raw: String): WithdrawInputParser.Result {
        return WithdrawInputParser.parse(raw) { lightningAddressManager.isValidLightningAddress(it) }
    }

    private fun onDestinationChanged(text: String) {
        parsedDestination = parseDestination(text)
        when (parsedDestination) {
            is WithdrawInputParser.Result.Bolt11 -> {
                showDestinationStatus(getString(R.string.withdraw_destination_detected_invoice))
                destinationContinueButton.isEnabled = true
            }
            is WithdrawInputParser.Result.LightningAddress -> {
                showDestinationStatus(getString(R.string.withdraw_destination_detected_address))
                destinationContinueButton.isEnabled = true
            }
            WithdrawInputParser.Result.Invalid -> {
                if (text.isBlank()) {
                    destinationStatus.text = ""
                } else {
                    destinationStatus.setTextColor(getColor(R.color.color_text_tertiary))
                    destinationStatus.text = getString(R.string.withdraw_destination_invalid)
                }
                destinationContinueButton.isEnabled = false
            }
        }
    }

    private fun showDestinationStatus(message: String) {
        destinationStatus.setTextColor(getColor(R.color.color_success_green))
        destinationStatus.text = message
    }

    private fun showDestinationError(message: String) {
        destinationStatus.setTextColor(getColor(R.color.color_error))
        destinationStatus.text = message
    }

    private fun pasteDestinationFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipText = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        if (clipText.isNullOrBlank()) {
            destinationInput.shake()
            showDestinationError(getString(R.string.withdraw_destination_clipboard_invalid))
            return
        }
        setDestinationFromExternal(clipText, R.string.withdraw_destination_clipboard_invalid)
    }

    /** Fill the input from scan/paste, rejecting content that parses to nothing. */
    private fun setDestinationFromExternal(raw: String, errorRes: Int) {
        when (val result = parseDestination(raw)) {
            is WithdrawInputParser.Result.Bolt11 -> destinationInput.setText(result.invoice)
            is WithdrawInputParser.Result.LightningAddress -> destinationInput.setText(result.address)
            WithdrawInputParser.Result.Invalid -> {
                destinationInput.shake()
                showDestinationError(getString(errorRes))
            }
        }
    }

    private fun processInvoice(invoice: String) {
        if (invoice.isBlank()) {
            showDestinationError(getString(R.string.withdraw_lightning_error_enter_invoice))
            return
        }

        setLoading(true)

        lifecycleScope.launch {
            try {
                val wallet = CashuWalletManager.getWallet()
                if (wallet == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@WithdrawLightningActivity,
                            getString(R.string.withdraw_lightning_error_wallet_not_initialized),
                            Toast.LENGTH_SHORT
                        ).show()
                        setLoading(false)
                    }
                    return@launch
                }

                // Get melt quote
                val meltQuoteObj = MintUrl(mintUrl)
                val unit = com.electricdreams.numo.core.util.MintManager.getInstance(this@WithdrawLightningActivity).getPreferredUnit()
                val mintWallet = wallet.getWallet(meltQuoteObj, com.electricdreams.numo.core.cashu.CashuWalletManager.getCurrencyUnit(unit))
                    ?: throw Exception("Failed to get wallet for mint: $mintUrl")
                val meltQuote = withContext(Dispatchers.IO) {
                    mintWallet.meltQuote(org.cashudevkit.PaymentMethod.Bolt11, invoice, null,null)
                }
                WalletLogger.log("OUT", meltQuote.amount.value.toLong(), mintUrl, "Invoice melt quote requested")
                
                withContext(Dispatchers.Main) {

                    setLoading(false)
                    
                    // Check if we have enough balance (including fee reserve)
                    val totalRequired = meltQuote.amount.value.toLong() + meltQuote.feeReserve.value.toLong()
                    if (totalRequired > balance) {
                        val maxAmount = (balance * (1 - FEE_BUFFER_PERCENT)).toLong()
                        destinationInput.shake()
                        showDestinationError(
                            getString(R.string.withdraw_lightning_error_insufficient_balance, totalRequired, balance, maxAmount)
                        )
                        return@withContext
                    }

                    // Launch melt quote activity
                    launchMeltQuoteActivity(meltQuote, invoice, null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting melt quote for invoice", e)
                withContext(Dispatchers.Main) {
                    setLoading(false)
                    showDestinationError(
                        com.electricdreams.numo.feature.withdraw.WithdrawErrorMapper.resolve(this@WithdrawLightningActivity, e.message)
                    )
                }
            }
        }
    }

    private fun openAmountScreen(address: String) {
        val intent = Intent(this, com.electricdreams.numo.feature.withdraw.WithdrawAmountActivity::class.java)
        intent.putExtra(com.electricdreams.numo.feature.withdraw.WithdrawAmountActivity.EXTRA_MINT_URL, mintUrl)
        intent.putExtra(com.electricdreams.numo.feature.withdraw.WithdrawAmountActivity.EXTRA_BALANCE, balance)
        intent.putExtra(com.electricdreams.numo.feature.withdraw.WithdrawAmountActivity.EXTRA_LIGHTNING_ADDRESS, address)
        startActivity(intent)
    }

    private fun launchMeltQuoteActivity(
        meltQuote: org.cashudevkit.MeltQuote, 
        invoice: String?, 
        lightningAddress: String?
    ) {
        val intent = Intent(this, WithdrawMeltQuoteActivity::class.java)
        intent.putExtra("mint_url", mintUrl)
        intent.putExtra("quote_id", meltQuote.id)
        intent.putExtra("amount", meltQuote.amount.value.toLong())
        intent.putExtra("fee_reserve", meltQuote.feeReserve.value.toLong())
        intent.putExtra("invoice", invoice)
        intent.putExtra("lightning_address", lightningAddress)
        intent.putExtra("request", meltQuote.request)
        startActivity(intent)
    }

    private fun setLoading(loading: Boolean) {
        loadingOverlay.visibility = if (loading) View.VISIBLE else View.GONE
        
        // Animate loading overlay
        if (loading) {
            loadingOverlay.alpha = 0f
            loadingOverlay.animate()
                .alpha(1f)
                .setDuration(200)
                .start()
        }
        
        // Disable destination controls during loading
        destinationInput.isEnabled = !loading
        pasteButton.isEnabled = !loading
        scanButton.isEnabled = !loading
        suggestionChip.isEnabled = !loading
        destinationContinueButton.isEnabled =
            !loading && parsedDestination != WithdrawInputParser.Result.Invalid
    }

    override fun onStart() {
        super.onStart()
        BalanceRefreshBroadcast.register(this, balanceRefreshReceiver)
    }

    override fun onStop() {
        super.onStop()
        BalanceRefreshBroadcast.unregister(this, balanceRefreshReceiver)
    }

    override fun onResume() {
        super.onResume()
        // Refresh balance when returning to this activity
        refreshBalance()
    }

    /**
     * Refreshes the balance from the wallet and updates the UI.
     * Called when broadcast is received or when activity resumes.
     */
    private fun refreshBalance() {
        lifecycleScope.launch {
            try {
                val newBalance = withContext(Dispatchers.IO) {
                    CashuWalletManager.getBalanceForMint(mintUrl)
                }
                
                withContext(Dispatchers.Main) {
                    if (newBalance != balance) {
                        balance = newBalance
                        val balanceAmount = Amount(balance, Amount.Currency.BTC)
                        balanceText.text = balanceAmount.toString()
                        updateFiatDisplay(balance)
                        Log.d(TAG, "Balance updated to: $balance sats")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing balance", e)
            }
        }
    }
}
