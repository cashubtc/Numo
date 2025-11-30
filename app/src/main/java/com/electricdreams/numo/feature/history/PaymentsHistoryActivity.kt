package com.electricdreams.numo.feature.history

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.electricdreams.numo.feature.enableEdgeToEdgeWithPill
import com.electricdreams.numo.PaymentRequestActivity
import com.electricdreams.numo.R
import com.electricdreams.numo.core.data.model.PaymentHistoryEntry
import com.electricdreams.numo.ui.adapter.PaymentsHistoryAdapter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type
import java.util.Collections

class PaymentsHistoryActivity : AppCompatActivity() {

    private lateinit var adapter: PaymentsHistoryAdapter
    private var emptyView: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        // Let history content run behind the gesture nav pill for a modern look
        enableEdgeToEdgeWithPill(this, lightNavIcons = true)

        // Setup Back Button
        val backButton: View? = findViewById(R.id.back_button)
        backButton?.setOnClickListener { finish() }

        // Setup RecyclerView
        val recyclerView: RecyclerView = findViewById(R.id.history_recycler_view)
        emptyView = findViewById(R.id.empty_view)

        adapter = PaymentsHistoryAdapter().apply {
            setOnItemClickListener { entry, position ->
                handleEntryClick(entry, position)
            }
        }

        recyclerView.adapter = adapter
        recyclerView.layoutManager = LinearLayoutManager(this)

        // Load and display history
        loadHistory()
    }

    override fun onResume() {
        super.onResume()
        // Reload history when returning (e.g., after resuming a pending payment)
        loadHistory()
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
        val intent = Intent(this, PaymentRequestActivity::class.java).apply {
            putExtra(PaymentRequestActivity.EXTRA_PAYMENT_AMOUNT, entry.amount)
            putExtra(PaymentRequestActivity.EXTRA_FORMATTED_AMOUNT, entry.formattedAmount)
            putExtra(PaymentRequestActivity.EXTRA_RESUME_PAYMENT_ID, entry.id)
            // Pass lightning quote info if available for resume
            entry.lightningQuoteId?.let {
                putExtra(PaymentRequestActivity.EXTRA_LIGHTNING_QUOTE_ID, it)
            }
            entry.lightningMintUrl?.let {
                putExtra(PaymentRequestActivity.EXTRA_LIGHTNING_MINT_URL, it)
            }
            entry.lightningInvoice?.let {
                putExtra(PaymentRequestActivity.EXTRA_LIGHTNING_INVOICE, it)
            }
            // Pass nostr info for resuming Cashu over Nostr
            entry.nostrSecretHex?.let {
                putExtra(PaymentRequestActivity.EXTRA_NOSTR_SECRET_HEX, it)
            }
            entry.nostrNprofile?.let {
                putExtra(PaymentRequestActivity.EXTRA_NOSTR_NPROFILE, it)
            }
        }
        startActivityForResult(intent, REQUEST_RESUME_PAYMENT)
    }

    private fun showTransactionDetails(entry: PaymentHistoryEntry, position: Int) {
        val intent = Intent(this, TransactionDetailActivity::class.java).apply {
            putExtra(TransactionDetailActivity.EXTRA_TRANSACTION_TOKEN, entry.token)
            putExtra(TransactionDetailActivity.EXTRA_TRANSACTION_AMOUNT, entry.amount)
            putExtra(TransactionDetailActivity.EXTRA_TRANSACTION_DATE, entry.date.time)
            putExtra(TransactionDetailActivity.EXTRA_TRANSACTION_UNIT, entry.getUnit())
            putExtra(TransactionDetailActivity.EXTRA_TRANSACTION_ENTRY_UNIT, entry.getEntryUnit())
            putExtra(TransactionDetailActivity.EXTRA_TRANSACTION_ENTERED_AMOUNT, entry.enteredAmount)
            entry.bitcoinPrice?.let {
                putExtra(TransactionDetailActivity.EXTRA_TRANSACTION_BITCOIN_PRICE, it)
            }
            putExtra(TransactionDetailActivity.EXTRA_TRANSACTION_MINT_URL, entry.mintUrl)
            putExtra(TransactionDetailActivity.EXTRA_TRANSACTION_PAYMENT_REQUEST, entry.paymentRequest)
            putExtra(TransactionDetailActivity.EXTRA_TRANSACTION_POSITION, position)
            putExtra(TransactionDetailActivity.EXTRA_TRANSACTION_PAYMENT_TYPE, entry.paymentType)
            putExtra(TransactionDetailActivity.EXTRA_TRANSACTION_LIGHTNING_INVOICE, entry.lightningInvoice)
            putExtra(TransactionDetailActivity.EXTRA_CHECKOUT_BASKET_JSON, entry.checkoutBasketJson)
            putExtra(TransactionDetailActivity.EXTRA_BASKET_ID, entry.basketId)
            putExtra(TransactionDetailActivity.EXTRA_TRANSACTION_TIP_AMOUNT, entry.tipAmountSats)
            putExtra(TransactionDetailActivity.EXTRA_TRANSACTION_TIP_PERCENTAGE, entry.tipPercentage)
        }

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

    private fun loadHistory() {
        val history = getPaymentHistory().toMutableList()
        Collections.reverse(history) // Show newest first
        adapter.setEntries(history)

        val isEmpty = history.isEmpty()
        emptyView?.visibility = if (isEmpty) View.VISIBLE else View.GONE
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
                    checkoutBasketJson = existing.checkoutBasketJson, // Preserve basket data
                    basketId = existing.basketId, // Preserve basket ID
                    tipAmountSats = existing.tipAmountSats, // Preserve tip info
                    tipPercentage = existing.tipPercentage, // Preserve tip info
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
        ) {
            val history = getPaymentHistory(context).toMutableList()
            history.add(
                PaymentHistoryEntry(
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
                ),
            )

            val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            prefs.edit().putString(KEY_HISTORY, Gson().toJson(history)).apply()
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
