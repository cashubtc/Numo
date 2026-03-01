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
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.electricdreams.numo.R
import com.electricdreams.numo.core.cashu.CashuWalletManager
import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.core.util.BalanceRefreshBroadcast
import com.electricdreams.numo.core.util.LightningAddressManager
import com.electricdreams.numo.core.util.MintManager
import com.electricdreams.numo.ui.components.WithdrawAddressCard
import com.electricdreams.numo.ui.components.WithdrawInvoiceCard
import com.electricdreams.numo.ui.util.QrCodeGenerator
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cashudevkit.CurrencyUnit
import org.cashudevkit.MintUrl
import org.cashudevkit.SendKind
import org.cashudevkit.SendOptions
import org.cashudevkit.SplitTarget

/**
 * Premium Apple-like activity for withdrawing balance from a mint via Lightning.
 * 
 * Features:
 * - Beautiful card-based design
 * - Separate cards for Invoice and Lightning Address
 * - Smooth entrance animations
 * - Elegant loading states
 * - Professional UX suitable for checkout operators
 * - Shared lightning address with auto-withdraw feature
 */
class WithdrawLightningActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "WithdrawLightning"
        private const val FEE_BUFFER_PERCENT = 0.02 // 2% buffer for fees
    }

    private lateinit var mintUrl: String
    private var balance: Long = 0
    private lateinit var mintManager: MintManager
    private lateinit var lightningAddressManager: LightningAddressManager

    // Views
    private lateinit var backButton: ImageButton
    private lateinit var balanceCard: MaterialCardView
    private lateinit var mintNameText: TextView
    private lateinit var balanceText: TextView
    private lateinit var invoiceCard: WithdrawInvoiceCard
    private lateinit var addressCard: WithdrawAddressCard
    private lateinit var loadingOverlay: FrameLayout

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

    // Balance refresh receiver
    private val balanceRefreshReceiver: BroadcastReceiver = BalanceRefreshBroadcast.createReceiver { reason ->
        Log.d(TAG, "Balance refresh broadcast received: $reason")
        refreshBalance()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_withdraw_lightning)

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
        startEntranceAnimations()
    }

    private fun initViews() {
        backButton = findViewById(R.id.back_button)
        balanceCard = findViewById(R.id.balance_card)
        mintNameText = findViewById(R.id.mint_name_text)
        balanceText = findViewById(R.id.balance_text)
        invoiceCard = findViewById(R.id.invoice_card)
        addressCard = findViewById(R.id.address_card)
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
        backButton.setOnClickListener { 
            finish() 
        }

        // Invoice card continue listener
        invoiceCard.setOnContinueListener(object : WithdrawInvoiceCard.OnContinueListener {
            override fun onContinue(invoice: String) {
                processInvoice(invoice)
            }
        })

        // Address card continue listener
        addressCard.setOnContinueListener(object : WithdrawAddressCard.OnContinueListener {
            override fun onContinue(address: String, amountSats: Long) {
                processLightningAddress(address, amountSats)
            }
        })

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
            Toast.makeText(this, "Insufficient balance", Toast.LENGTH_SHORT).show()
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
                val mintWallet = walletRepo.getWallet(MintUrl(mintUrl), CurrencyUnit.Sat)
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
                    metadata = emptyMap()
                )

                val preparedSend = withContext(Dispatchers.IO) {
                    mintWallet.prepareSend(amount, options)
                }

                // Confirm and get token
                val token = withContext(Dispatchers.IO) {
                    preparedSend.confirm(null)
                }
                
                val tokenString = token.encode()

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
                    createTokenButton.text = "Token Created"
                    
                    setLoading(false)
                    refreshBalance()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error creating token", e)
                withContext(Dispatchers.Main) {
                    setLoading(false)
                    Toast.makeText(
                        this@WithdrawLightningActivity,
                        getString(R.string.withdraw_lightning_error_generic, e.message ?: ""),
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
    }

    private fun prefillFields() {
        // Pre-fill lightning address from shared LightningAddressManager
        // This is the same address used by auto-withdraw
        val savedAddress = lightningAddressManager.getLightningAddress()
        if (savedAddress.isNotEmpty()) {
            addressCard.setAddress(savedAddress)
        }

        // Pre-fill amount with balance - 2% fee buffer
        val suggestedAmount = (balance * (1 - FEE_BUFFER_PERCENT)).toLong()
        if (suggestedAmount > 0) {
            addressCard.setSuggestedAmount(suggestedAmount)
        }
    }

    private fun startEntranceAnimations() {
        // Balance card slide in from top
        balanceCard.alpha = 0f
        balanceCard.translationY = -40f
        balanceCard.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(400)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()

        // Balance text scale
        balanceText.scaleX = 0.8f
        balanceText.scaleY = 0.8f
        balanceText.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setStartDelay(200)
            .setDuration(350)
            .setInterpolator(OvershootInterpolator(2f))
            .start()

        // Cards stagger entrance
        invoiceCard.animateEntrance(300)
        addressCard.animateEntrance(450)
    }

    private fun processInvoice(invoice: String) {
        if (invoice.isBlank()) {
            Toast.makeText(
                this,
                getString(R.string.withdraw_lightning_error_enter_invoice),
                Toast.LENGTH_SHORT
            ).show()
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
                            "Wallet not initialized", 
                            Toast.LENGTH_SHORT
                        ).show()
                        setLoading(false)
                    }
                    return@launch
                }

                // Get melt quote
                val meltQuoteObj = MintUrl(mintUrl)
                val mintWallet = wallet.getWallet(meltQuoteObj, CurrencyUnit.Sat)
                    ?: throw Exception("Failed to get wallet for mint: $mintUrl")
                val meltQuote = withContext(Dispatchers.IO) {
                    mintWallet.meltQuote(org.cashudevkit.PaymentMethod.Bolt11, invoice, null,null)
                }

                withContext(Dispatchers.Main) {
                    setLoading(false)
                    
                    // Check if we have enough balance (including fee reserve)
                    val totalRequired = meltQuote.amount.value.toLong() + meltQuote.feeReserve.value.toLong()
                    if (totalRequired > balance) {
                        val maxAmount = (balance * (1 - FEE_BUFFER_PERCENT)).toLong()
                        Toast.makeText(
                            this@WithdrawLightningActivity,
                            "Insufficient balance. Amount + fees ($totalRequired sats) exceeds your balance ($balance sats). Try an amount under $maxAmount sats.",
                            Toast.LENGTH_LONG
                        ).show()
                        return@withContext
                    }

                    // Launch melt quote activity
                    launchMeltQuoteActivity(meltQuote, invoice, null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting melt quote for invoice", e)
                withContext(Dispatchers.Main) {
                    setLoading(false)
                    Toast.makeText(
                        this@WithdrawLightningActivity,
                        getString(R.string.withdraw_lightning_error_generic, e.message ?: ""),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun processLightningAddress(address: String, amountSats: Long) {
        if (address.isBlank()) {
            Toast.makeText(
                this,
                getString(R.string.withdraw_lightning_error_enter_address),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (amountSats <= 0) {
            Toast.makeText(
                this,
                getString(R.string.withdraw_lightning_error_enter_valid_amount),
                Toast.LENGTH_SHORT
            ).show()
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
                            "Wallet not initialized", 
                            Toast.LENGTH_SHORT
                        ).show()
                        setLoading(false)
                    }
                    return@launch
                }

                // Get melt quote for Lightning address
                val amountMsat = amountSats * 1000
                val mintWallet = wallet.getWallet(MintUrl(mintUrl), CurrencyUnit.Sat)
                    ?: throw Exception("Failed to get wallet for mint: $mintUrl")
                val meltQuote = withContext(Dispatchers.IO) {
                    mintWallet.meltLightningAddressQuote(address, org.cashudevkit.Amount(amountMsat.toULong()))
                }

                withContext(Dispatchers.Main) {
                    setLoading(false)
                    
                    // Check if we have enough balance (including fee reserve)
                    val totalRequired = meltQuote.amount.value.toLong() + meltQuote.feeReserve.value.toLong()
                    if (totalRequired > balance) {
                        val maxAmount = (balance * (1 - FEE_BUFFER_PERCENT)).toLong()
                        Toast.makeText(
                            this@WithdrawLightningActivity,
                            "Insufficient balance. Amount + fees ($totalRequired sats) exceeds your balance ($balance sats). Try an amount under $maxAmount sats.",
                            Toast.LENGTH_LONG
                        ).show()
                        return@withContext
                    }

                    // Save the lightning address to shared manager
                    // This persists it for both manual and auto withdrawals
                    lightningAddressManager.setLightningAddress(address)

                    // Launch melt quote activity
                    launchMeltQuoteActivity(meltQuote, null, address)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting melt quote for Lightning address", e)
                withContext(Dispatchers.Main) {
                    setLoading(false)
                    Toast.makeText(
                        this@WithdrawLightningActivity,
                        getString(R.string.withdraw_lightning_error_generic, e.message ?: ""),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
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
        
        // Disable cards during loading
        invoiceCard.setCardEnabled(!loading)
        addressCard.setCardEnabled(!loading)
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
                        
                        // Update suggested amount in address card
                        val suggestedAmount = (balance * (1 - FEE_BUFFER_PERCENT)).toLong()
                        if (suggestedAmount > 0) {
                            addressCard.setSuggestedAmount(suggestedAmount)
                        }
                        
                        Log.d(TAG, "Balance updated to: $balance sats")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing balance", e)
            }
        }
    }
}
