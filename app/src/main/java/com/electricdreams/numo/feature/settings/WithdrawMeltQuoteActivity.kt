package com.electricdreams.numo.feature.settings

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.electricdreams.numo.R
import com.electricdreams.numo.core.cashu.CashuWalletManager
import com.electricdreams.numo.core.data.model.PaymentHistoryEntry
import com.electricdreams.numo.feature.history.PaymentsHistoryActivity
import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.core.util.MintManager
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cashudevkit.MeltOptions
import org.cashudevkit.QuoteState
import java.util.Date

/**
 * Activity to confirm and execute a melt (withdraw) operation
 */
class WithdrawMeltQuoteActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "WithdrawMeltQuote"
    }

    private lateinit var mintUrl: String
    private lateinit var quoteId: String
    private var amount: Long = 0
    private var feeReserve: Long = 0
    private var invoice: String? = null
    private var lightningAddress: String? = null
    private var request: String = ""
    private lateinit var mintManager: MintManager

    // Views
    private lateinit var backButton: ImageButton
    private lateinit var summaryText: TextView
    private lateinit var destinationText: TextView
    private lateinit var amountText: TextView
    private lateinit var feeText: TextView
    private lateinit var totalText: TextView
    private lateinit var confirmButton: Button
    private lateinit var loadingSpinner: ProgressBar
    private lateinit var loadingText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_withdraw_melt_quote)

        mintUrl = intent.getStringExtra("mint_url") ?: ""
        quoteId = intent.getStringExtra("quote_id") ?: ""
        amount = intent.getLongExtra("amount", 0)
        feeReserve = intent.getLongExtra("fee_reserve", 0)
        invoice = intent.getStringExtra("invoice")
        lightningAddress = intent.getStringExtra("lightning_address")
        request = intent.getStringExtra("request") ?: ""
        mintManager = MintManager.getInstance(this)

        if (mintUrl.isEmpty() || quoteId.isEmpty()) {
            Toast.makeText(
                this,
                getString(R.string.withdraw_melt_error_invalid_data),
                Toast.LENGTH_SHORT
            ).show()
            finish()
            return
        }

        initViews()
        setupListeners()
        displayQuoteInfo()
    }

    private fun initViews() {
        backButton = findViewById(R.id.back_button)
        summaryText = findViewById(R.id.summary_text)
        destinationText = findViewById(R.id.destination_text)
        amountText = findViewById(R.id.amount_text)
        feeText = findViewById(R.id.fee_text)
        totalText = findViewById(R.id.total_text)
        confirmButton = findViewById(R.id.confirm_button)
        loadingSpinner = findViewById(R.id.loading_spinner)
        loadingText = findViewById(R.id.loading_text)
    }

    private fun setupListeners() {
        backButton.setOnClickListener { finish() }
        confirmButton.setOnClickListener { confirmWithdrawal() }
    }

    private fun displayQuoteInfo() {
        // Display destination
        val destination = when {
            !lightningAddress.isNullOrBlank() -> lightningAddress!!
            !invoice.isNullOrBlank() -> {
                // Abbreviate invoice for display
                if (invoice!!.length > 24) {
                    "${invoice!!.take(12)}...${invoice!!.takeLast(12)}"
                } else {
                    invoice!!
                }
            }
            else -> getString(R.string.withdraw_melt_destination_unknown)
        }
        destinationText.text = destination

        // Display amounts
        val amountObj = Amount(amount, Amount.Currency.BTC)
        val feeObj = Amount(feeReserve, Amount.Currency.BTC)
        val totalObj = Amount(amount + feeReserve, Amount.Currency.BTC)

        amountText.text = amountObj.toString()
        feeText.text = feeObj.toString()
        totalText.text = totalObj.toString()

        // Summary text
        val mintName = mintManager.getMintDisplayName(mintUrl)
        summaryText.text = getString(
            R.string.withdraw_melt_summary,
            mintName
        )
    }

    private fun confirmWithdrawal() {
        setLoading(true)

        lifecycleScope.launch {
            var historyEntryId: String? = null
            
            try {
                val wallet = CashuWalletManager.getWallet()
                if (wallet == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@WithdrawMeltQuoteActivity,
                            getString(R.string.withdraw_melt_error_wallet_not_initialized),
                            Toast.LENGTH_SHORT
                        ).show()
                        setLoading(false)
                    }
                    return@launch
                }

                // Create pending transaction history entry
                val historyEntry = PaymentHistoryEntry(
                    token = "",
                    amount = amount,
                    date = Date(),
                    rawUnit = "sat",
                    rawEntryUnit = "sat",
                    enteredAmount = amount,
                    bitcoinPrice = null,
                    mintUrl = mintUrl,
                    paymentRequest = null,
                    rawStatus = PaymentHistoryEntry.STATUS_PENDING,
                    paymentType = PaymentHistoryEntry.TYPE_LIGHTNING,
                    lightningInvoice = request,
                    lightningQuoteId = quoteId,
                    lightningMintUrl = mintUrl
                )

                withContext(Dispatchers.Main) {
                    addEntryToHistory(historyEntry)
                    historyEntryId = historyEntry.id
                }

                // Execute melt operation
                val melted = withContext(Dispatchers.IO) {
                    wallet.meltWithMint(org.cashudevkit.MintUrl(mintUrl), quoteId)
                }

                // Check melt state
                val state = melted.state
                Log.d(TAG, "Melt state after melt: $state")

                // Check melt state
                val meltQuote = withContext(Dispatchers.IO) {
                    wallet.checkMeltQuote(org.cashudevkit.MintUrl(mintUrl), quoteId)
                }

                Log.d(TAG, "Melt state after check: $meltQuote.state")

                withContext(Dispatchers.Main) {
                    setLoading(false)

                    when (meltQuote.state) {
                        QuoteState.PAID  -> {
                            // Update history entry to completed
                            val updatedEntry = historyEntry.copy(
                                rawStatus = PaymentHistoryEntry.STATUS_COMPLETED
                            )
                            updateEntryInHistory(updatedEntry)

                            // Show success activity
                            showPaymentSuccess()
                        }
                        QuoteState.UNPAID -> {
                            // Remove from history
                            historyEntryId?.let {
                                removeEntryFromHistory(it)
                            }
                            showPaymentError(
                                getString(R.string.withdraw_melt_error_invoice_not_paid)
                            )
                        }
                        QuoteState.PENDING -> {
                            // Keep in history as pending
                            showPaymentError(
                                getString(R.string.withdraw_melt_error_pending)
                            )
                        }
                        else -> {
                            // Remove from history
                            historyEntryId?.let {
                                removeEntryFromHistory(it)
                            }
                            showPaymentError(
                                getString(R.string.withdraw_melt_error_unknown_state)
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error executing melt", e)
                withContext(Dispatchers.Main) {
                    setLoading(false)
                    
                    // Remove from history on error
                    historyEntryId?.let {
                        removeEntryFromHistory(it)
                    }
                    
                    showPaymentError(
                        getString(
                            R.string.withdraw_melt_error_generic,
                            e.message ?: ""
                        )
                    )
                }
            }
        }
    }

    private fun addEntryToHistory(entry: PaymentHistoryEntry) {
        val history = PaymentsHistoryActivity.getPaymentHistory(this).toMutableList()
        history.add(entry)
        saveHistory(history)
    }

    private fun updateEntryInHistory(entry: PaymentHistoryEntry) {
        val history = PaymentsHistoryActivity.getPaymentHistory(this).toMutableList()
        val index = history.indexOfFirst { it.id == entry.id }
        if (index >= 0) {
            history[index] = entry
            saveHistory(history)
        }
    }

    private fun removeEntryFromHistory(entryId: String) {
        val history = PaymentsHistoryActivity.getPaymentHistory(this).toMutableList()
        history.removeAll { it.id == entryId }
        saveHistory(history)
    }

    private fun saveHistory(history: List<PaymentHistoryEntry>) {
        val prefs = getSharedPreferences("PaymentHistory", MODE_PRIVATE)
        prefs.edit().putString("history", Gson().toJson(history)).apply()
    }

    private fun showPaymentSuccess() {
        val intent = Intent(this, WithdrawSuccessActivity::class.java)
        intent.putExtra("amount", amount)
        val destinationLabel = lightningAddress
            ?: getString(R.string.withdraw_melt_destination_invoice_fallback)
        intent.putExtra("destination", destinationLabel)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        finish()
    }

    private fun showPaymentError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun setLoading(loading: Boolean) {
        loadingSpinner.visibility = if (loading) View.VISIBLE else View.GONE
        loadingText.visibility = if (loading) View.VISIBLE else View.GONE
        confirmButton.isEnabled = !loading
        backButton.isEnabled = !loading
    }
}
