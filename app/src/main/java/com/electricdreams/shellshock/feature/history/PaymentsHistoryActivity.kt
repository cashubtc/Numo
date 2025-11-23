package com.electricdreams.shellshock.feature.history

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
import com.electricdreams.shellshock.R
import com.electricdreams.shellshock.core.data.model.PaymentHistoryEntry
import com.electricdreams.shellshock.ui.adapter.PaymentsHistoryAdapter
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

        // Setup Back Button
        val backButton: View? = findViewById(R.id.back_button)
        backButton?.setOnClickListener { finish() }

        // Setup RecyclerView
        val recyclerView: RecyclerView = findViewById(R.id.history_recycler_view)
        emptyView = findViewById(R.id.empty_view)

        adapter = PaymentsHistoryAdapter().apply {
            setOnItemClickListener { entry, position ->
                showTransactionDetails(entry, position)
            }
        }

        recyclerView.adapter = adapter
        recyclerView.layoutManager = LinearLayoutManager(this)

        // Load and display history
        loadHistory()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_TRANSACTION_DETAIL && resultCode == RESULT_OK && data != null) {
            val positionToDelete = data.getIntExtra("position_to_delete", -1)
            if (positionToDelete >= 0) {
                deletePaymentFromHistory(positionToDelete)
            }
        }
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

        val chooserIntent = Intent.createChooser(uriIntent, "Open payment with...").apply {
            putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(shareIntent))
        }

        try {
            startActivity(chooserIntent)
        } catch (e: Exception) {
            Toast.makeText(this, "No apps available to handle this payment", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDeleteConfirmation(entry: PaymentHistoryEntry, position: Int) {
        AlertDialog.Builder(this)
            .setTitle("Delete Payment")
            .setMessage("Are you sure you want to delete this payment from history?")
            .setPositiveButton("Delete") { _, _ -> deletePaymentFromHistory(position) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showClearHistoryConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Clear History")
            .setMessage("Are you sure you want to clear all payment history? This action cannot be undone.")
            .setPositiveButton("Clear All") { _, _ -> clearAllHistory() }
            .setNegativeButton("Cancel", null)
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

        @JvmStatic
        fun getPaymentHistory(context: Context): List<PaymentHistoryEntry> {
            val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val json = prefs.getString(KEY_HISTORY, "[]")
            val type: Type = object : TypeToken<ArrayList<PaymentHistoryEntry>>() {}.type
            return Gson().fromJson(json, type)
        }

        /**
         * Add a payment to history with comprehensive information.
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
                ),
            )

            val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            prefs.edit().putString(KEY_HISTORY, Gson().toJson(history)).apply()
        }

        /**
         * Legacy method for backward compatibility.
         * @deprecated Use the full-parameter addToHistory instead.
         */
        @Deprecated("Use addToHistory with full parameters")
        @JvmStatic
        fun addToHistory(context: Context, token: String, amount: Long) {
            addToHistory(context, token, amount, "sat", "sat", amount, null, null, null)
        }
    }
}
