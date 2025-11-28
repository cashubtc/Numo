package com.electricdreams.numo.feature.settings

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.electricdreams.numo.R
import com.electricdreams.numo.core.cashu.CashuWalletManager
import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.core.util.MintManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cashudevkit.MintUrl

/**
 * Activity for withdrawing balance from a mint via Lightning
 * Supports both Lightning invoices and Lightning addresses
 */
class WithdrawLightningActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "WithdrawLightning"
        private const val FEE_BUFFER_PERCENT = 0.02 // 2% buffer for fees
        private const val PREFS_NAME = "WithdrawLightningPreferences"
        private const val KEY_LAST_LIGHTNING_ADDRESS = "lastLightningAddress"
    }

    private lateinit var mintUrl: String
    private var balance: Long = 0
    private lateinit var mintManager: MintManager
    private lateinit var preferences: SharedPreferences

    // Views
    private lateinit var backButton: ImageButton
    private lateinit var mintNameText: TextView
    private lateinit var balanceText: TextView
    private lateinit var invoiceOption: LinearLayout
    private lateinit var lightningAddressOption: LinearLayout
    private lateinit var invoiceExpandedContent: LinearLayout
    private lateinit var addressExpandedContent: LinearLayout
    private lateinit var invoiceInput: EditText
    private lateinit var addressInput: EditText
    private lateinit var amountInput: EditText
    private lateinit var continueInvoiceButton: Button
    private lateinit var continueAddressButton: Button
    private lateinit var loadingSpinner: ProgressBar

    private var isInvoiceExpanded = false
    private var isAddressExpanded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_withdraw_lightning)

        mintUrl = intent.getStringExtra("mint_url") ?: ""
        balance = intent.getLongExtra("balance", 0)
        mintManager = MintManager.getInstance(this)
        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

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
        backButton = findViewById(R.id.back_button)
        mintNameText = findViewById(R.id.mint_name_text)
        balanceText = findViewById(R.id.balance_text)
        invoiceOption = findViewById(R.id.invoice_option)
        lightningAddressOption = findViewById(R.id.lightning_address_option)
        invoiceExpandedContent = findViewById(R.id.invoice_expanded_content)
        addressExpandedContent = findViewById(R.id.address_expanded_content)
        invoiceInput = findViewById(R.id.invoice_input)
        addressInput = findViewById(R.id.address_input)
        amountInput = findViewById(R.id.amount_input)
        continueInvoiceButton = findViewById(R.id.continue_invoice_button)
        continueAddressButton = findViewById(R.id.continue_address_button)
        loadingSpinner = findViewById(R.id.loading_spinner)
    }

    private fun setupListeners() {
        backButton.setOnClickListener { finish() }

        // Invoice option toggle
        invoiceOption.setOnClickListener {
            toggleInvoiceOption()
        }

        // Lightning address option toggle
        lightningAddressOption.setOnClickListener {
            toggleAddressOption()
        }

        // Continue buttons
        continueInvoiceButton.setOnClickListener {
            processInvoice()
        }

        continueAddressButton.setOnClickListener {
            processLightningAddress()
        }

        // Text watchers to enable/disable buttons
        invoiceInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                continueInvoiceButton.isEnabled = !s.isNullOrBlank()
            }
        })

        addressInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateAddressButtonState()
            }
        })

        amountInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateAddressButtonState()
            }
        })
    }

    private fun updateAddressButtonState() {
        val hasAddress = !addressInput.text.isNullOrBlank()
        val hasAmount = !amountInput.text.isNullOrBlank() && amountInput.text.toString().toLongOrNull() != null
        continueAddressButton.isEnabled = hasAddress && hasAmount
    }

    private fun toggleInvoiceOption() {
        isInvoiceExpanded = !isInvoiceExpanded
        invoiceExpandedContent.visibility = if (isInvoiceExpanded) View.VISIBLE else View.GONE

        if (isInvoiceExpanded && isAddressExpanded) {
            // Collapse the other option
            isAddressExpanded = false
            addressExpandedContent.visibility = View.GONE
        }
    }

    private fun toggleAddressOption() {
        isAddressExpanded = !isAddressExpanded
        addressExpandedContent.visibility = if (isAddressExpanded) View.VISIBLE else View.GONE

        if (isAddressExpanded && isInvoiceExpanded) {
            // Collapse the other option
            isInvoiceExpanded = false
            invoiceExpandedContent.visibility = View.GONE
        }
    }

    private fun displayMintInfo() {
        val displayName = mintManager.getMintDisplayName(mintUrl)
        mintNameText.text = displayName

        val balanceAmount = Amount(balance, Amount.Currency.BTC)
        balanceText.text = balanceAmount.toString()
    }

    private fun prefillFields() {
        // Pre-fill lightning address from preferences
        val lastAddress = preferences.getString(KEY_LAST_LIGHTNING_ADDRESS, "")
        if (!lastAddress.isNullOrEmpty()) {
            addressInput.setText(lastAddress)
        }

        // Pre-fill amount with balance - 2%
        val suggestedAmount = (balance * (1 - FEE_BUFFER_PERCENT)).toLong()
        if (suggestedAmount > 0) {
            amountInput.setText(suggestedAmount.toString())
        }
    }

    private fun processInvoice() {
        val invoice = invoiceInput.text.toString().trim()
        if (invoice.isBlank()) {
            Toast.makeText(
                this,
                getString(R.string.withdraw_lightning_error_enter_invoice),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        // Show loading
        setLoading(true)

        lifecycleScope.launch {
            try {
                val wallet = CashuWalletManager.getWallet()
                if (wallet == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@WithdrawLightningActivity, "Wallet not initialized", Toast.LENGTH_SHORT).show()
                        setLoading(false)
                    }
                    return@launch
                }

                // Get melt quote
                val meltQuote = withContext(Dispatchers.IO) {
                    wallet.meltQuote(MintUrl(mintUrl), invoice, null)
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

    private fun processLightningAddress() {
        val address = addressInput.text.toString().trim()
        val amountSats = amountInput.text.toString().toLongOrNull()

        if (address.isBlank()) {
            Toast.makeText(
                this,
                getString(R.string.withdraw_lightning_error_enter_address),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (amountSats == null || amountSats <= 0) {
            Toast.makeText(
                this,
                getString(R.string.withdraw_lightning_error_enter_valid_amount),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        // Show loading
        setLoading(true)

        lifecycleScope.launch {
            try {
                val wallet = CashuWalletManager.getWallet()
                if (wallet == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@WithdrawLightningActivity, "Wallet not initialized", Toast.LENGTH_SHORT).show()
                        setLoading(false)
                    }
                    return@launch
                }

                // Get melt quote for Lightning address
                val amountMsat = amountSats * 1000
                val meltQuote = withContext(Dispatchers.IO) {
                    wallet.meltLightningAddressQuote(MintUrl(mintUrl), address, amountMsat.toULong())
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

                    // Save the lightning address to preferences
                    preferences.edit().putString(KEY_LAST_LIGHTNING_ADDRESS, address).apply()

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

    private fun launchMeltQuoteActivity(meltQuote: org.cashudevkit.MeltQuote, invoice: String?, lightningAddress: String?) {
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
        loadingSpinner.visibility = if (loading) View.VISIBLE else View.GONE
        continueInvoiceButton.isEnabled = !loading
        continueAddressButton.isEnabled = !loading
        invoiceInput.isEnabled = !loading
        addressInput.isEnabled = !loading
        amountInput.isEnabled = !loading
    }
}
