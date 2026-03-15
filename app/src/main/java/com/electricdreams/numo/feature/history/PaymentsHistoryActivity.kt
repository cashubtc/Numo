package com.electricdreams.numo.feature.history

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.electricdreams.numo.feature.enableEdgeToEdgeWithPill
import com.electricdreams.numo.R
import androidx.appcompat.widget.PopupMenu
import com.electricdreams.numo.core.cashu.CashuWalletManager
import com.electricdreams.numo.core.data.model.PaymentHistoryEntry
import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.core.util.CurrencyManager
import com.electricdreams.numo.core.worker.BitcoinPriceWorker
import com.electricdreams.numo.databinding.ActivityHistoryBinding
import com.electricdreams.numo.feature.autowithdraw.AutoWithdrawManager
import com.electricdreams.numo.feature.autowithdraw.WithdrawHistoryEntry
import com.electricdreams.numo.payment.PaymentIntentFactory
import com.electricdreams.numo.ui.adapter.PaymentsHistoryAdapter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.reflect.Type
import java.util.Collections
import java.util.Date

class PaymentsHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var adapter: PaymentsHistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Let history content run behind the gesture nav pill for a modern look
        enableEdgeToEdgeWithPill(this, lightNavIcons = true)

        // Setup Back Button
        binding.backButton?.setOnClickListener { finish() }

        // Setup overflow menu button
        binding.overflowButton?.setOnClickListener { showOverflowMenu(it) }

        // Setup RecyclerView
        adapter = PaymentsHistoryAdapter().apply {
            setOnItemClickListener { entry, position ->
                handleEntryClick(entry, position)
            }
        }

        binding.historyRecyclerView.adapter = adapter
        binding.historyRecyclerView.layoutManager = LinearLayoutManager(this)

        // Load and display history
        loadHistory()

        // Load wallet balance
        loadBalance()
    }

    override fun onResume() {
        super.onResume()
        // Reload history when returning (e.g., after resuming a pending payment)
        loadHistory()
        loadBalance()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQUEST_TRANSACTION_DETAIL -> {
                if (resultCode == RESULT_OK && data != null) {
                    val positionToDelete = data.getIntExtra("position_to_delete", -1)
                    if (positionToDelete >= 0) {
                        deletePaymentFromHistory(positionToDelete)
                    }
                }
            }
            REQUEST_RESUME_PAYMENT -> {
                // Payment resumed - reload history to reflect any changes
                loadHistory()
            }
        }
    }

    private fun loadBalance() {
        lifecycleScope.launch {
            try {
                val balances = withContext(Dispatchers.IO) {
                    CashuWalletManager.getAllMintBalances()
                }
                val totalSats = balances.values.sum()

                // Display sat balance
                val satAmount = Amount(totalSats, Amount.Currency.BTC)
                binding.balanceSats?.text = satAmount.toString()

                // Display fiat balance
                val currencyCode = CurrencyManager.getInstance(this@PaymentsHistoryActivity)
                    .getCurrentCurrency()
                val btcPrice = BitcoinPriceWorker.getInstance(this@PaymentsHistoryActivity)
                    .getCurrentPrice()

                if (btcPrice > 0) {
                    val fiatValue = (totalSats.toDouble() / 100_000_000.0) * btcPrice
                    val fiatCurrency = Amount.Currency.fromCode(currencyCode)
                    val fiatMinorUnits = kotlin.math.round(fiatValue * 100).toLong()
                    val fiatAmount = Amount(fiatMinorUnits, fiatCurrency)
                    binding.balanceFiat?.text = fiatAmount.toString()
                } else {
                    // No price available, show sats as primary
                    binding.balanceFiat?.text = satAmount.toString()
                    binding.balanceSats?.visibility = View.GONE
                }
            } catch (e: Exception) {
                // Silently handle - balance display is supplementary
                binding.balanceFiat?.text = ""
                binding.balanceSats?.text = ""
            }
        }
    }

    private fun handleEntryClick(entry: PaymentHistoryEntry, position: Int) {
        if (entry.isPending()) {
            // Resume the pending payment
            resumePendingPayment(entry)
        } else {
            // Show transaction details
            showTransactionDetails(entry, position)
        }
    }

    private fun resumePendingPayment(entry: PaymentHistoryEntry) {
        val intent = PaymentIntentFactory.createResumePaymentIntent(this, entry)
        startActivityForResult(intent, REQUEST_RESUME_PAYMENT)
    }

    private fun showTransactionDetails(entry: PaymentHistoryEntry, position: Int) {
        val intent = PaymentIntentFactory.createTransactionDetailIntent(this, entry, position)
        startActivityForResult(intent, REQUEST_TRANSACTION_DETAIL)
    }

    private fun openPaymentWithApp(token: String) {
        val cashuUri = "cashu:$token"
        val uriIntent = Intent(Intent.ACTION_VIEW, Uri.parse(cashuUri)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, cashuUri)
        }

        val chooserIntent = Intent.createChooser(uriIntent, getString(R.string.history_open_with_title)).apply {
            putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(shareIntent))
        }

        try {
            startActivity(chooserIntent)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.history_toast_no_app), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDeleteConfirmation(entry: PaymentHistoryEntry, position: Int) {
        AlertDialog.Builder(this)
            .setTitle(R.string.history_dialog_delete_title)
            .setMessage(R.string.history_dialog_delete_message)
            .setPositiveButton(R.string.history_dialog_delete_positive) { _, _ -> deletePaymentFromHistory(position) }
            .setNegativeButton(R.string.history_dialog_delete_negative, null)
            .show()
    }

    private fun showClearHistoryConfirmation() {
        AlertDialog.Builder(this)
            .setTitle(R.string.history_dialog_clear_title)
            .setMessage(R.string.history_dialog_clear_message)
            .setPositiveButton(R.string.history_dialog_clear_positive) { _, _ -> clearAllHistory() }
            .setNegativeButton(R.string.history_dialog_clear_negative, null)
            .show()
    }

    private fun showOverflowMenu(anchor: View) {
        val popup = PopupMenu(this, anchor, android.view.Gravity.END)
        popup.menuInflater.inflate(R.menu.menu_activity_history, popup.menu)
        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.menu_export_activity -> {
                    Toast.makeText(this, R.string.history_export_coming_soon, Toast.LENGTH_SHORT).show()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun loadHistory() {
        val paymentHistory = getPaymentHistory()

        // Fetch withdrawal history and convert to PaymentHistoryEntry
        val withdrawHistory = AutoWithdrawManager.getInstance(this).getHistory()
        val withdrawAsPayments = withdrawHistory
            .filter { it.status != WithdrawHistoryEntry.STATUS_FAILED }
            .map { w ->
                PaymentHistoryEntry(
                    id = w.id,
                    token = w.token ?: "",
                    amount = -w.amountSats, // Negative = outgoing
                    date = Date(w.timestamp),
                    rawUnit = "sat",
                    rawEntryUnit = "sat",
                    enteredAmount = w.amountSats,
                    bitcoinPrice = null,
                    mintUrl = w.mintUrl,
                    paymentRequest = w.destination.ifBlank { w.lightningAddress },
                    rawStatus = when (w.status) {
                        WithdrawHistoryEntry.STATUS_COMPLETED -> PaymentHistoryEntry.STATUS_COMPLETED
                        WithdrawHistoryEntry.STATUS_PENDING -> PaymentHistoryEntry.STATUS_PENDING
                        else -> PaymentHistoryEntry.STATUS_COMPLETED
                    },
                    paymentType = PaymentHistoryEntry.TYPE_LIGHTNING,
                )
            }

        // Merge and sort by date descending (newest first)
        val merged = (paymentHistory + withdrawAsPayments)
            .sortedByDescending { it.date.time }

        adapter.setEntries(merged)

        val isEmpty = merged.isEmpty()
        binding.emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
    }

    private fun clearAllHistory() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit().putString(KEY_HISTORY, "[]").apply()
        loadHistory()
    }

    private fun deletePaymentFromHistory(position: Int) {
        val history = getPaymentHistory().toMutableList()
        Collections.reverse(history)
        if (position in 0 until history.size) {
            history.removeAt(position)
            Collections.reverse(history)

            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            prefs.edit().putString(KEY_HISTORY, Gson().toJson(history)).apply()

            loadHistory()
        }
    }

    private fun getPaymentHistory(): List<PaymentHistoryEntry> = getPaymentHistory(this)

    companion object {
        private const val PREFS_NAME = "PaymentHistory"
        private const val KEY_HISTORY = "history"
        private const val REQUEST_TRANSACTION_DETAIL = 1001
        private const val REQUEST_RESUME_PAYMENT = 1002

        @JvmStatic
        fun getPaymentHistory(context: Context): List<PaymentHistoryEntry> {
            val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val json = prefs.getString(KEY_HISTORY, "[]")
            val type: Type = object : TypeToken<ArrayList<PaymentHistoryEntry>>() {}.type
            return Gson().fromJson(json, type)
        }

        @JvmStatic
        fun getPaymentEntryById(context: Context, paymentId: String): PaymentHistoryEntry? {
            return getPaymentHistory(context).firstOrNull { it.id == paymentId }
        }

        /**
         * Add a pending payment to history when payment request is initiated.
         * Returns the ID of the created entry.
         */
        @JvmStatic
        fun addPendingPayment(
            context: Context,
            amount: Long,
            entryUnit: String,
            enteredAmount: Long,
            bitcoinPrice: Double?,
            paymentRequest: String?,
            formattedAmount: String?,
            checkoutBasketJson: String? = null,
            basketId: String? = null,
            tipAmountSats: Long = 0,
            tipPercentage: Int = 0,
        ): String {
            val entry = PaymentHistoryEntry.createPending(
                amount = amount,
                entryUnit = entryUnit,
                enteredAmount = enteredAmount,
                bitcoinPrice = bitcoinPrice,
                paymentRequest = paymentRequest,
                formattedAmount = formattedAmount,
                checkoutBasketJson = checkoutBasketJson,
                basketId = basketId,
                tipAmountSats = tipAmountSats,
                tipPercentage = tipPercentage,
            )

            val history = getPaymentHistory(context).toMutableList()
            history.add(entry)

            val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            prefs.edit().putString(KEY_HISTORY, Gson().toJson(history)).apply()

            return entry.id
        }

        /**
         * Update a pending payment to completed with full payment details.
         */
        @JvmStatic
        fun completePendingPayment(
            context: Context,
            paymentId: String,
            token: String,
            paymentType: String,
            mintUrl: String?,
            lightningInvoice: String? = null,
            lightningQuoteId: String? = null,
            lightningMintUrl: String? = null,
        ) {
            val history = getPaymentHistory(context).toMutableList()
            val index = history.indexOfFirst { it.id == paymentId }

            if (index >= 0) {
                val existing = history[index]
                val updated = PaymentHistoryEntry(
                    id = existing.id,
                    token = token,
                    amount = existing.amount,
                    date = existing.date,
                    rawUnit = existing.getUnit(),
                    rawEntryUnit = existing.getEntryUnit(),
                    enteredAmount = existing.enteredAmount,
                    bitcoinPrice = existing.bitcoinPrice,
                    mintUrl = mintUrl ?: existing.mintUrl,
                    paymentRequest = existing.paymentRequest,
                    rawStatus = PaymentHistoryEntry.STATUS_COMPLETED,
                    paymentType = paymentType,
                    lightningInvoice = lightningInvoice,
                    lightningQuoteId = lightningQuoteId,
                    lightningMintUrl = lightningMintUrl,
                    formattedAmount = existing.formattedAmount,
                    nostrNprofile = existing.nostrNprofile,
                    nostrSecretHex = existing.nostrSecretHex,
                    checkoutBasketJson = existing.checkoutBasketJson, // Preserve basket data
                    basketId = existing.basketId, // Preserve basket ID
                    tipAmountSats = existing.tipAmountSats, // Preserve tip info
                    tipPercentage = existing.tipPercentage, // Preserve tip info
                    label = existing.label, // Preserve label
                )
                history[index] = updated

                val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                prefs.edit().putString(KEY_HISTORY, Gson().toJson(history)).apply()
            }
        }

        /**
         * Update a pending payment with Lightning quote info (for resume capability).
         */
        @JvmStatic
        fun updatePendingWithLightningInfo(
            context: Context,
            paymentId: String,
            lightningInvoice: String? = null,
            lightningQuoteId: String? = null,
            lightningMintUrl: String? = null,
            swapToLightningMintJson: String? = null,
        ) {
            val history = getPaymentHistory(context).toMutableList()
            val index = history.indexOfFirst { it.id == paymentId }

            if (index >= 0) {
                val existing = history[index]
                val updated = PaymentHistoryEntry(
                    id = existing.id,
                    token = existing.token,
                    amount = existing.amount,
                    date = existing.date,
                    rawUnit = existing.getUnit(),
                    rawEntryUnit = existing.getEntryUnit(),
                    enteredAmount = existing.enteredAmount,
                    bitcoinPrice = existing.bitcoinPrice,
                    mintUrl = existing.mintUrl,
                    paymentRequest = existing.paymentRequest,
                    rawStatus = existing.getStatus(),
                    paymentType = existing.paymentType,
                    lightningInvoice = lightningInvoice ?: existing.lightningInvoice,
                    lightningQuoteId = lightningQuoteId ?: existing.lightningQuoteId,
                    lightningMintUrl = lightningMintUrl ?: existing.lightningMintUrl,
                    formattedAmount = existing.formattedAmount,
                    nostrNprofile = existing.nostrNprofile,
                    nostrSecretHex = existing.nostrSecretHex,
                    checkoutBasketJson = existing.checkoutBasketJson, // Preserve basket data
                    basketId = existing.basketId, // Preserve basket ID
                    tipAmountSats = existing.tipAmountSats, // Preserve tip info
                    tipPercentage = existing.tipPercentage, // Preserve tip info
                    swapToLightningMintJson = swapToLightningMintJson ?: existing.swapToLightningMintJson,
                )
                history[index] = updated

                val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                prefs.edit().putString(KEY_HISTORY, Gson().toJson(history)).apply()
            }
        }

        /**
         * Update a pending payment with Nostr info (for resume capability).
         */
        @JvmStatic
        fun updatePendingWithNostrInfo(
            context: Context,
            paymentId: String,
            nostrSecretHex: String,
            nostrNprofile: String,
        ) {
            val history = getPaymentHistory(context).toMutableList()
            val index = history.indexOfFirst { it.id == paymentId }

            if (index >= 0) {
                val existing = history[index]
                val updated = PaymentHistoryEntry(
                    id = existing.id,
                    token = existing.token,
                    amount = existing.amount,
                    date = existing.date,
                    rawUnit = existing.getUnit(),
                    rawEntryUnit = existing.getEntryUnit(),
                    enteredAmount = existing.enteredAmount,
                    bitcoinPrice = existing.bitcoinPrice,
                    mintUrl = existing.mintUrl,
                    paymentRequest = existing.paymentRequest,
                    rawStatus = existing.getStatus(),
                    paymentType = existing.paymentType,
                    lightningInvoice = existing.lightningInvoice,
                    lightningQuoteId = existing.lightningQuoteId,
                    lightningMintUrl = existing.lightningMintUrl,
                    formattedAmount = existing.formattedAmount,
                    nostrNprofile = nostrNprofile,
                    nostrSecretHex = nostrSecretHex,
                    checkoutBasketJson = existing.checkoutBasketJson, // Preserve basket data
                    basketId = existing.basketId, // Preserve basket ID
                    tipAmountSats = existing.tipAmountSats, // Preserve tip info
                    tipPercentage = existing.tipPercentage, // Preserve tip info
                    label = existing.label, // Preserve label
                )
                history[index] = updated

                val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                prefs.edit().putString(KEY_HISTORY, Gson().toJson(history)).apply()
            }
        }

        /**
         * Update a pending payment with tip information.
         */
        @JvmStatic
        fun updatePendingWithTipInfo(
            context: Context,
            paymentId: String,
            tipAmountSats: Long,
            tipPercentage: Int,
            newTotalAmount: Long,
        ) {
            val history = getPaymentHistory(context).toMutableList()
            val index = history.indexOfFirst { it.id == paymentId }

            if (index >= 0) {
                val existing = history[index]
                val updated = PaymentHistoryEntry(
                    id = existing.id,
                    token = existing.token,
                    amount = newTotalAmount,
                    date = existing.date,
                    rawUnit = existing.getUnit(),
                    rawEntryUnit = existing.getEntryUnit(),
                    enteredAmount = existing.enteredAmount,
                    bitcoinPrice = existing.bitcoinPrice,
                    mintUrl = existing.mintUrl,
                    paymentRequest = existing.paymentRequest,
                    rawStatus = existing.getStatus(),
                    paymentType = existing.paymentType,
                    lightningInvoice = existing.lightningInvoice,
                    lightningQuoteId = existing.lightningQuoteId,
                    lightningMintUrl = existing.lightningMintUrl,
                    formattedAmount = existing.formattedAmount,
                    nostrNprofile = existing.nostrNprofile,
                    nostrSecretHex = existing.nostrSecretHex,
                    checkoutBasketJson = existing.checkoutBasketJson,
                    basketId = existing.basketId, // Preserve basket ID
                    tipAmountSats = tipAmountSats,
                    tipPercentage = tipPercentage,
                )
                history[index] = updated

                val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                prefs.edit().putString(KEY_HISTORY, Gson().toJson(history)).apply()
            }
        }

        /**
         * Cancel a pending payment (mark as cancelled or delete).
         */
        @JvmStatic
        fun cancelPendingPayment(context: Context, paymentId: String) {
            val history = getPaymentHistory(context).toMutableList()
            // Remove cancelled pending payments (they're not useful)
            history.removeAll { it.id == paymentId && it.isPending() }

            val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            prefs.edit().putString(KEY_HISTORY, Gson().toJson(history)).apply()
        }

        /**
         * Update the label on a payment history entry.
         */
        @JvmStatic
        fun updateLabel(context: Context, paymentId: String, label: String?) {
            val history = getPaymentHistory(context).toMutableList()
            val index = history.indexOfFirst { it.id == paymentId }

            if (index >= 0) {
                val existing = history[index]
                val updated = PaymentHistoryEntry(
                    id = existing.id,
                    token = existing.token,
                    amount = existing.amount,
                    date = existing.date,
                    rawUnit = existing.getUnit(),
                    rawEntryUnit = existing.getEntryUnit(),
                    enteredAmount = existing.enteredAmount,
                    bitcoinPrice = existing.bitcoinPrice,
                    mintUrl = existing.mintUrl,
                    paymentRequest = existing.paymentRequest,
                    rawStatus = existing.getStatus(),
                    paymentType = existing.paymentType,
                    lightningInvoice = existing.lightningInvoice,
                    lightningQuoteId = existing.lightningQuoteId,
                    lightningMintUrl = existing.lightningMintUrl,
                    formattedAmount = existing.formattedAmount,
                    nostrNprofile = existing.nostrNprofile,
                    nostrSecretHex = existing.nostrSecretHex,
                    checkoutBasketJson = existing.checkoutBasketJson,
                    basketId = existing.basketId,
                    tipAmountSats = existing.tipAmountSats,
                    tipPercentage = existing.tipPercentage,
                    swapToLightningMintJson = existing.swapToLightningMintJson,
                    label = label?.ifBlank { null },
                )
                history[index] = updated

                val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                prefs.edit().putString(KEY_HISTORY, Gson().toJson(history)).apply()
            }
        }

        /**
         * Add a payment to history with comprehensive information (legacy method).
         */
        @JvmStatic
        fun addToHistory(
            context: Context,
            token: String,
            amount: Long,
            unit: String,
            entryUnit: String,
            enteredAmount: Long,
            bitcoinPrice: Double?,
            mintUrl: String?,
            paymentRequest: String?,
        ): String {
            val history = getPaymentHistory(context).toMutableList()
            val entry = PaymentHistoryEntry(
                token = token,
                amount = amount,
                date = java.util.Date(),
                rawUnit = unit,
                rawEntryUnit = entryUnit,
                enteredAmount = enteredAmount,
                bitcoinPrice = bitcoinPrice,
                mintUrl = mintUrl,
                paymentRequest = paymentRequest,
                rawStatus = PaymentHistoryEntry.STATUS_COMPLETED,
                paymentType = PaymentHistoryEntry.TYPE_CASHU,
            )
            history.add(entry)

            val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            prefs.edit().putString(KEY_HISTORY, Gson().toJson(history)).apply()

            return entry.id
        }

        /**
         * Legacy method for backward compatibility.
         * @deprecated Use addToHistory with full parameters.
         */
        @Deprecated("Use addToHistory with full parameters")
        @JvmStatic
        fun addToHistory(context: Context, token: String, amount: Long) {
            addToHistory(context, token, amount, "sat", "sat", amount, null, null, null)
        }
    }
}
