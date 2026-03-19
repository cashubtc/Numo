package com.electricdreams.numo.feature.history

import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import com.electricdreams.numo.R
import com.electricdreams.numo.core.data.model.HistoryEntry
import com.electricdreams.numo.core.data.model.PaymentHistoryEntry
import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.core.util.CurrencyManager
import com.electricdreams.numo.core.util.MintManager
import com.electricdreams.numo.feature.autowithdraw.AutoWithdrawManager
import com.electricdreams.numo.feature.autowithdraw.WithdrawHistoryEntry
import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Helper for exporting activity/transaction history to a CSV file.
 */
object ActivityCsvExportHelper {

    private const val TAG = "ActivityCsvExport"

    /**
     * Export activity history to a CSV [uri].
     *
     * @param context Activity or application context used for IO and toasts.
     * @param uri     The URI returned by the system file picker (CreateDocument).
     */
    fun exportActivityToCsvUri(context: Context, uri: Uri) {
        try {
            val outputStream = context.contentResolver.openOutputStream(uri)
            if (outputStream == null) {
                Toast.makeText(
                    context,
                    context.getString(R.string.history_export_error),
                    Toast.LENGTH_SHORT
                ).show()
                return
            }

            outputStream.use { stream ->
                val success = exportActivityToCsv(context, stream)
                if (success) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.history_export_success),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        context,
                        context.getString(R.string.history_export_error),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error exporting activity CSV: ${e.message}", e)
            Toast.makeText(
                context,
                context.getString(R.string.history_export_error),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun exportActivityToCsv(context: Context, outputStream: OutputStream): Boolean {
        try {
            val writer = BufferedWriter(OutputStreamWriter(outputStream))

            // Write header row
            val header = arrayOf(
                "Date",
                "Payment Direction",
                "Payment Method",
                "Status",
                "Amount (BTC)",
                "Amount (Fiat)",
                "Exchange Rate",
                "Mint",
                "Invoice/Cashu Request",
                "Sent To",
                "Label",
                "Transaction ID",
            )
            writer.write(header.joinToString(",") { formatCsvField(it) })
            writer.write("\n")

            // Load all transactions (same merge logic as PaymentsHistoryActivity.loadHistory)
            val allEntries = loadAllTransactions(context)

            val mintManager = MintManager.getInstance(context)
            val currencyManager = CurrencyManager.getInstance(context)
            val defaultCurrencyCode = currencyManager.getCurrentCurrency()
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

            for (entry in allEntries) {
                val isWithdrawal = entry.amount < 0

                // Date
                val date = dateFormat.format(entry.date)

                // Payment Direction
                val direction = if (isWithdrawal) "Withdrawal" else "Received"

                // Payment Method
                val method = when (entry) {
                    is PaymentHistoryEntry -> when (entry.paymentType) {
                        PaymentHistoryEntry.TYPE_LIGHTNING -> "Lightning"
                        PaymentHistoryEntry.TYPE_CASHU -> "Cashu"
                        else -> "Cashu"
                    }
                    is WithdrawHistoryEntry -> "Lightning"
                    else -> "Cashu"
                }

                // Status
                val status = when (entry.status) {
                    PaymentHistoryEntry.STATUS_COMPLETED -> "Completed"
                    PaymentHistoryEntry.STATUS_PENDING -> "Pending"
                    else -> "Completed"
                }

                // Amount (BTC) - absolute value in sats
                val amountSats = kotlin.math.abs(entry.amount).toString()

                // Determine the fiat currency to use
                val fiatCurrencyCode = if (entry.getEntryUnit() != "sat" &&
                    entry.getEntryUnit() != "BTC" &&
                    entry.getEntryUnit() != "sats"
                ) {
                    entry.getEntryUnit()
                } else {
                    defaultCurrencyCode
                }
                val fiatCurrency = Amount.Currency.fromCode(fiatCurrencyCode)

                // Amount (Fiat)
                val amountFiat = computeFiatAmount(entry, fiatCurrency)

                // Exchange Rate
                val bitcoinPrice = (entry as? PaymentHistoryEntry)?.bitcoinPrice
                val exchangeRate = if (bitcoinPrice != null && bitcoinPrice > 0) {
                    val priceMinorUnits = kotlin.math.round(bitcoinPrice * 100).toLong()
                    Amount(priceMinorUnits, fiatCurrency).toString()
                } else {
                    ""
                }

                // Mint
                val mintUrl = when (entry) {
                    is PaymentHistoryEntry -> entry.mintUrl ?: entry.lightningMintUrl
                    is WithdrawHistoryEntry -> entry.mintUrl
                    else -> entry.mintUrl
                }
                val mint = if (!mintUrl.isNullOrEmpty()) {
                    mintManager.getMintDisplayName(mintUrl)
                } else {
                    ""
                }

                // Invoice/Cashu Request (full, non-truncated)
                val invoiceOrToken = when (entry) {
                    is PaymentHistoryEntry -> entry.lightningInvoice
                        ?: entry.token.ifEmpty { null }
                        ?: ""
                    is WithdrawHistoryEntry -> entry.token ?: ""
                    else -> ""
                }

                // Sent To (withdrawals only)
                val sentTo = when (entry) {
                    is PaymentHistoryEntry -> if (isWithdrawal) entry.paymentRequest ?: "" else ""
                    is WithdrawHistoryEntry -> entry.destination.ifBlank { entry.lightningAddress ?: "" }
                    else -> ""
                }

                // Label
                val label = entry.label ?: ""

                // Transaction ID
                val transactionId = entry.id

                val row = arrayOf(
                    date,
                    direction,
                    method,
                    status,
                    amountSats,
                    amountFiat,
                    exchangeRate,
                    mint,
                    invoiceOrToken,
                    sentTo,
                    label,
                    transactionId,
                )
                writer.write(row.joinToString(",") { formatCsvField(it) })
                writer.write("\n")
            }

            writer.flush()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error generating activity CSV: ${e.message}", e)
            return false
        }
    }

    /**
     * Load all transactions (payments + withdrawals), merged and sorted by date descending.
     * Same logic as PaymentsHistoryActivity.loadHistory().
     */
    private fun loadAllTransactions(context: Context): List<HistoryEntry> {
        val paymentHistory: List<HistoryEntry> = PaymentsHistoryActivity.getPaymentHistory(context)
        val withdrawHistory: List<HistoryEntry> = AutoWithdrawManager.getInstance(context)
            .getHistory()
            .filter { it.status != WithdrawHistoryEntry.STATUS_FAILED }

        return (paymentHistory + withdrawHistory)
            .sortedByDescending { it.date.time }
    }

    /**
     * Compute the fiat amount string for a transaction using the stored exchange rate.
     */
    private fun computeFiatAmount(entry: HistoryEntry, fiatCurrency: Amount.Currency): String {
        // If the entry was made in fiat, use the entered amount directly
        val entryUnit = entry.getEntryUnit()
        if (entryUnit != "sat" && entryUnit != "BTC" && entryUnit != "sats" && entry.enteredAmount > 0) {
            val entryCurrency = Amount.Currency.fromCode(entryUnit)
            return Amount(entry.enteredAmount, entryCurrency).toString()
        }

        // Otherwise, calculate from sats using stored bitcoin price
        val btcPrice = (entry as? PaymentHistoryEntry)?.bitcoinPrice
        if (btcPrice != null && btcPrice > 0) {
            val absSats = kotlin.math.abs(entry.amount)
            val fiatValue = (absSats.toDouble() / 100_000_000.0) * btcPrice
            val fiatMinorUnits = kotlin.math.round(fiatValue * 100).toLong()
            return Amount(fiatMinorUnits, fiatCurrency).toString()
        }

        return ""
    }

    /**
     * Format a field for CSV output, escaping quotes and wrapping if needed.
     */
    private fun formatCsvField(field: String): String {
        var result = field
        if (result.contains("\"")) {
            result = result.replace("\"", "\"\"")
        }
        if (result.contains(",") || result.contains("\"") || result.contains("\n")) {
            result = "\"$result\""
        }
        return result
    }
}
