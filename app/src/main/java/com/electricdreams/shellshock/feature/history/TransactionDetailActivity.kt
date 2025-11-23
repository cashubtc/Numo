package com.electricdreams.shellshock.feature.history

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.electricdreams.shellshock.R
import com.electricdreams.shellshock.core.data.model.PaymentHistoryEntry
import com.electricdreams.shellshock.core.model.Amount
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen activity to display detailed transaction information
 * following Cash App design guidelines.
 */
class TransactionDetailActivity : AppCompatActivity() {

    private lateinit var entry: PaymentHistoryEntry
    private var position: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transaction_detail)

        // Get transaction data from intent
        val intent = intent
        val token = intent.getStringExtra(EXTRA_TRANSACTION_TOKEN)
        val amount = intent.getLongExtra(EXTRA_TRANSACTION_AMOUNT, 0L)
        val dateMillis = intent.getLongExtra(EXTRA_TRANSACTION_DATE, System.currentTimeMillis())
        val unit = intent.getStringExtra(EXTRA_TRANSACTION_UNIT)
        val entryUnit = intent.getStringExtra(EXTRA_TRANSACTION_ENTRY_UNIT)
        val enteredAmount = intent.getLongExtra(EXTRA_TRANSACTION_ENTERED_AMOUNT, amount)
        val bitcoinPriceValue = intent.getDoubleExtra(EXTRA_TRANSACTION_BITCOIN_PRICE, -1.0)
        val bitcoinPrice = if (bitcoinPriceValue > 0) bitcoinPriceValue else null
        val mintUrl = intent.getStringExtra(EXTRA_TRANSACTION_MINT_URL)
        val paymentRequest = intent.getStringExtra(EXTRA_TRANSACTION_PAYMENT_REQUEST)
        position = intent.getIntExtra(EXTRA_TRANSACTION_POSITION, -1)

        // Create entry object (normalize nullable unit fields via Kotlin defaults)
        entry = PaymentHistoryEntry(
            token = token ?: "",
            amount = amount,
            date = Date(dateMillis),
            rawUnit = unit,
            rawEntryUnit = entryUnit,
            enteredAmount = enteredAmount,
            bitcoinPrice = bitcoinPrice,
            mintUrl = mintUrl,
            paymentRequest = paymentRequest,
        )

        setupViews()
    }

    private fun setupViews() {
        // Back button
        findViewById<ImageButton>(R.id.back_button).setOnClickListener { finish() }

        // Share button
        findViewById<ImageButton>(R.id.share_button).setOnClickListener { shareTransaction() }

        // Display transaction details
        displayTransactionDetails()

        // Setup action buttons
        setupActionButtons()
    }

    private fun displayTransactionDetails() {
        // Amount display
        val amountText: TextView = findViewById(R.id.detail_amount)
        val amountValueText: TextView = findViewById(R.id.detail_amount_value)

        val currency = Amount.Currency.fromCode(entry.getUnit())
        val amount = Amount(entry.amount, currency)
        val formattedAmount = amount.toString()

        amountText.text = formattedAmount
        amountValueText.text = formattedAmount

        // Date
        val dateText: TextView = findViewById(R.id.detail_date)
        val dateFormat = SimpleDateFormat("MMMM d, yyyy 'at' h:mm a", Locale.getDefault())
        dateText.text = dateFormat.format(entry.date)

        // Mint name/URL
        val mintNameText: TextView = findViewById(R.id.mint_name)
        val mintUrlText: TextView = findViewById(R.id.detail_mint_url)

        val mintUrl = entry.mintUrl
        if (!mintUrl.isNullOrEmpty()) {
            val mintName = extractMintName(mintUrl)
            mintNameText.text = "From $mintName"
            mintUrlText.text = mintUrl
        } else {
            mintNameText.visibility = View.GONE
            mintUrlText.text = "Unknown"
        }

        // Token unit
        val tokenUnitText: TextView = findViewById(R.id.detail_token_unit)
        tokenUnitText.text = entry.getUnit()

        // Entry unit
        val entryUnitText: TextView = findViewById(R.id.detail_entry_unit)
        entryUnitText.text = entry.getEntryUnit()

        // Entered Amount
        val enteredAmountText: TextView = findViewById(R.id.detail_entered_amount)
        val enteredAmountRow: View = findViewById(R.id.entered_amount_row)
        val enteredAmountDivider: View = findViewById(R.id.entered_amount_divider)

        if (entry.getEntryUnit() != "sat") {
            val entryCurrency = Amount.Currency.fromCode(entry.getEntryUnit())
            val enteredAmount = Amount(entry.enteredAmount, entryCurrency)
            enteredAmountText.text = enteredAmount.toString()
            enteredAmountRow.visibility = View.VISIBLE
            enteredAmountDivider.visibility = View.VISIBLE
        } else {
            enteredAmountRow.visibility = View.GONE
            enteredAmountDivider.visibility = View.GONE
        }

        // Bitcoin Price
        val bitcoinPriceText: TextView = findViewById(R.id.detail_bitcoin_price)
        val bitcoinPriceRow: View = findViewById(R.id.bitcoin_price_row)
        val bitcoinPriceDivider: View = findViewById(R.id.bitcoin_price_divider)

        val btcPrice = entry.bitcoinPrice
        if (btcPrice != null && btcPrice > 0) {
            val formattedPrice = String.format(Locale.US, "$%,.2f", btcPrice)
            bitcoinPriceText.text = formattedPrice
            bitcoinPriceRow.visibility = View.VISIBLE
            bitcoinPriceDivider.visibility = View.VISIBLE
        } else {
            bitcoinPriceRow.visibility = View.GONE
            bitcoinPriceDivider.visibility = View.GONE
        }

        // Token
        val tokenText: TextView = findViewById(R.id.detail_token)
        tokenText.text = entry.token

        // Payment request (if available)
        val paymentRequestHeader: TextView = findViewById(R.id.payment_request_header)
        val paymentRequestText: TextView = findViewById(R.id.detail_payment_request)

        val request = entry.paymentRequest
        if (!request.isNullOrEmpty()) {
            paymentRequestHeader.visibility = View.VISIBLE
            paymentRequestText.visibility = View.VISIBLE
            paymentRequestText.text = request
        } else {
            paymentRequestHeader.visibility = View.GONE
            paymentRequestText.visibility = View.GONE
        }
    }

    private fun extractMintName(mintUrl: String): String {
        return try {
            val uri = Uri.parse(mintUrl)
            var host = uri.host
            if (host != null) {
                if (host.startsWith("www.")) {
                    host = host.substring(4)
                }
                host
            } else {
                mintUrl
            }
        } catch (_: Exception) {
            mintUrl
        }
    }

    private fun setupActionButtons() {
        val copyButton: Button = findViewById(R.id.btn_copy)
        val openWithButton: Button = findViewById(R.id.btn_open_with)
        val deleteButton: Button = findViewById(R.id.btn_delete)

        copyButton.setOnClickListener { copyToken() }
        openWithButton.setOnClickListener { openWithApp() }
        deleteButton.setOnClickListener { showDeleteConfirmation() }
    }

    private fun copyToken() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Cashu Token", entry.token)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Token copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun openWithApp() {
        val cashuUri = "cashu:${entry.token}"
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

    private fun shareTransaction() {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"

            val currency = Amount.Currency.fromCode(entry.getUnit())
            val amount = Amount(entry.amount, currency)

            val shareText = "Cashu Payment\n" +
                "Amount: ${amount}\n" +
                "Token: ${entry.token}"

            putExtra(Intent.EXTRA_TEXT, shareText)
        }

        startActivity(Intent.createChooser(shareIntent, "Share Transaction"))
    }

    private fun showDeleteConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Delete Payment")
            .setMessage("Are you sure you want to delete this payment from history?")
            .setPositiveButton("Delete") { _, _ -> deleteTransaction() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteTransaction() {
        val resultIntent = Intent().apply {
            putExtra("position_to_delete", position)
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    companion object {
        const val EXTRA_TRANSACTION_TOKEN = "transaction_token"
        const val EXTRA_TRANSACTION_AMOUNT = "transaction_amount"
        const val EXTRA_TRANSACTION_DATE = "transaction_date"
        const val EXTRA_TRANSACTION_UNIT = "transaction_unit"
        const val EXTRA_TRANSACTION_ENTRY_UNIT = "transaction_entry_unit"
        const val EXTRA_TRANSACTION_ENTERED_AMOUNT = "transaction_entered_amount"
        const val EXTRA_TRANSACTION_BITCOIN_PRICE = "transaction_bitcoin_price"
        const val EXTRA_TRANSACTION_MINT_URL = "transaction_mint_url"
        const val EXTRA_TRANSACTION_PAYMENT_REQUEST = "transaction_payment_request"
        const val EXTRA_TRANSACTION_POSITION = "transaction_position"
    }
}
