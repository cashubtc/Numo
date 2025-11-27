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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.electricdreams.shellshock.R
import com.electricdreams.shellshock.core.data.model.PaymentHistoryEntry
import com.electricdreams.shellshock.core.model.Amount
import com.electricdreams.shellshock.core.model.CheckoutBasket
import com.electricdreams.shellshock.core.util.MintManager
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
    private var paymentType: String? = null
    private var lightningInvoice: String? = null
    private var checkoutBasketJson: String? = null
    private var tipAmountSats: Long = 0
    private var tipPercentage: Int = 0

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
        paymentType = intent.getStringExtra(EXTRA_TRANSACTION_PAYMENT_TYPE)
        lightningInvoice = intent.getStringExtra(EXTRA_TRANSACTION_LIGHTNING_INVOICE)
        checkoutBasketJson = intent.getStringExtra(EXTRA_CHECKOUT_BASKET_JSON)
        tipAmountSats = intent.getLongExtra(EXTRA_TRANSACTION_TIP_AMOUNT, 0)
        tipPercentage = intent.getIntExtra(EXTRA_TRANSACTION_TIP_PERCENTAGE, 0)

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
            tipAmountSats = tipAmountSats,
            tipPercentage = tipPercentage,
        )

        // DEBUG: Log received tip info
        android.util.Log.d("TransactionDetailActivity", "🧾 TRANSACTION DETAIL DEBUG:")
        android.util.Log.d("TransactionDetailActivity", "   💰 Total amount: $amount sats")
        android.util.Log.d("TransactionDetailActivity", "   📊 Entered amount: $enteredAmount ${entryUnit ?: "null"}")
        android.util.Log.d("TransactionDetailActivity", "   💸 Tip from intent: $tipAmountSats sats ($tipPercentage%)")
        android.util.Log.d("TransactionDetailActivity", "   💸 Tip from entry: ${entry.tipAmountSats} sats (${entry.tipPercentage}%)")
        android.util.Log.d("TransactionDetailActivity", "   🧮 Calculated base: ${entry.getBaseAmountSats()} sats")

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
        // Update hero icon based on payment type
        val heroIcon = findViewById<ImageView>(R.id.hero_icon)
        val transactionType = findViewById<TextView>(R.id.transaction_type)
        
        when (paymentType) {
            PaymentHistoryEntry.TYPE_LIGHTNING -> {
                heroIcon.setImageResource(R.drawable.ic_lightning_bolt)
                transactionType.text = "Lightning Payment"
            }
            PaymentHistoryEntry.TYPE_CASHU -> {
                heroIcon.setImageResource(R.drawable.ic_bitcoin)
                transactionType.text = "Cashu Payment"
            }
            else -> {
                heroIcon.setImageResource(R.drawable.ic_bitcoin)
                transactionType.text = "Payment Received"
            }
        }

        // Amount display - show BASE amount (excluding tip) for proper accounting
        val amountText: TextView = findViewById(R.id.detail_amount)
        val amountSubtitleText: TextView = findViewById(R.id.detail_amount_subtitle)
        val amountValueText: TextView = findViewById(R.id.detail_amount_value)

        // Parse basket to determine display mode
        val basket = CheckoutBasket.fromJson(checkoutBasketJson)
        val showSatsAsPrimary = basket?.let { 
            it.hasMixedPriceTypes() || it.getFiatItems().isEmpty() 
        } ?: (entry.getEntryUnit() == "sat")
        
        // Use BASE amount (excluding tip) for display - this is what was sold
        val baseAmountSats = entry.getBaseAmountSats()
        val baseSatAmount = Amount(baseAmountSats, Amount.Currency.BTC)
        
        if (showSatsAsPrimary) {
            // Primary: Sats (base amount)
            amountText.text = baseSatAmount.toString()
            amountValueText.text = baseSatAmount.toString()
            
            // Secondary: Fiat equivalent
            if (entry.enteredAmount > 0 && entry.getEntryUnit() != "sat") {
                val entryCurrency = Amount.Currency.fromCode(entry.getEntryUnit())
                val fiatAmount = Amount(entry.enteredAmount, entryCurrency)
                amountSubtitleText.text = "≈ $fiatAmount"
                amountSubtitleText.visibility = View.VISIBLE
            } else {
                amountSubtitleText.visibility = View.GONE
            }
        } else {
            // Primary: Fiat (entered amount - which is the base amount)
            val entryCurrency = Amount.Currency.fromCode(entry.getEntryUnit())
            val fiatAmount = Amount(entry.enteredAmount, entryCurrency)
            amountText.text = fiatAmount.toString()
            amountValueText.text = fiatAmount.toString()
            
            // Secondary: Sats paid (base amount, not total)
            amountSubtitleText.text = baseSatAmount.toString()
            amountSubtitleText.visibility = View.VISIBLE
        }

        // Date
        val dateText: TextView = findViewById(R.id.detail_date)
        val dateFormat = SimpleDateFormat("MMMM d, yyyy 'at' h:mm a", Locale.getDefault())
        dateText.text = dateFormat.format(entry.date)

        // Mint name/URL
        val mintNameText: TextView = findViewById(R.id.mint_name)
        val mintUrlText: TextView = findViewById(R.id.detail_mint_url)

        val mintUrl = entry.mintUrl
        if (!mintUrl.isNullOrEmpty()) {
            val mintName = getMintDisplayName(mintUrl)
            mintNameText.text = "From $mintName"
            mintUrlText.text = mintUrl
        } else {
            mintNameText.visibility = View.GONE
            mintUrlText.text = "Unknown"
        }

        // Payment Type Row
        val paymentTypeRow: View = findViewById(R.id.payment_type_row)
        val paymentTypeDivider: View = findViewById(R.id.payment_type_divider)
        val paymentTypeText: TextView = findViewById(R.id.detail_payment_type)
        
        if (paymentType != null) {
            paymentTypeRow.visibility = View.VISIBLE
            paymentTypeDivider.visibility = View.VISIBLE
            paymentTypeText.text = when (paymentType) {
                PaymentHistoryEntry.TYPE_LIGHTNING -> "⚡ Lightning"
                PaymentHistoryEntry.TYPE_CASHU -> "🥜 Cashu"
                else -> paymentType
            }
        } else {
            paymentTypeRow.visibility = View.GONE
            paymentTypeDivider.visibility = View.GONE
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

        // Tip Row (shown only if tip was added)
        val tipRow: View = findViewById(R.id.tip_row)
        val tipLabel: TextView = findViewById(R.id.tip_label)
        val tipAmountText: TextView = findViewById(R.id.detail_tip_amount)
        val tipFiatRow: View = findViewById(R.id.tip_fiat_row)
        val tipFiatText: TextView = findViewById(R.id.detail_tip_fiat)
        val tipDivider: View = findViewById(R.id.tip_divider)
        val totalPaidRow: View = findViewById(R.id.total_paid_row)
        val totalPaidText: TextView = findViewById(R.id.detail_total_paid)
        val totalPaidDivider: View = findViewById(R.id.total_paid_divider)

        // FIXED: Use entry.tipAmountSats (from stored data) not local tipAmountSats (from intent)
        if (entry.tipAmountSats > 0) {
            // Show tip row with label including percentage if applicable
            tipLabel.text = if (entry.tipPercentage > 0) "Added tip (${entry.tipPercentage}%)" else "Added tip"
            tipAmountText.text = Amount(entry.tipAmountSats, Amount.Currency.BTC).toString()
            tipRow.visibility = View.VISIBLE
            tipDivider.visibility = View.VISIBLE

            // Show fiat equivalent if we have bitcoin price
            if (btcPrice != null && btcPrice > 0) {
                val tipFiatValue = (entry.tipAmountSats.toDouble() / 100_000_000.0) * btcPrice * 100 // cents
                val entryCurrency = Amount.Currency.fromCode(entry.getEntryUnit())
                val tipFiatAmount = Amount(tipFiatValue.toLong(), entryCurrency)
                tipFiatText.text = "≈ $tipFiatAmount"
                tipFiatRow.visibility = View.VISIBLE
            } else {
                tipFiatRow.visibility = View.GONE
            }

            // Show total paid (base + tip)
            val totalAmount = Amount(entry.amount, Amount.Currency.BTC)
            totalPaidText.text = totalAmount.toString()
            totalPaidRow.visibility = View.VISIBLE
            totalPaidDivider.visibility = View.VISIBLE
        } else {
            tipRow.visibility = View.GONE
            tipFiatRow.visibility = View.GONE
            tipDivider.visibility = View.GONE
            totalPaidRow.visibility = View.GONE
            totalPaidDivider.visibility = View.GONE
        }

        // Lightning Invoice section
        val lightningInvoiceHeader: TextView = findViewById(R.id.lightning_invoice_header)
        val lightningInvoiceContainer: LinearLayout = findViewById(R.id.lightning_invoice_container)
        val lightningInvoiceText: TextView = findViewById(R.id.detail_lightning_invoice)
        
        if (!lightningInvoice.isNullOrEmpty()) {
            lightningInvoiceHeader.visibility = View.VISIBLE
            lightningInvoiceContainer.visibility = View.VISIBLE
            lightningInvoiceText.text = lightningInvoice
            
            // Make container clickable to copy invoice
            lightningInvoiceContainer.setOnClickListener {
                copyLightningInvoice()
            }
        } else {
            lightningInvoiceHeader.visibility = View.GONE
            lightningInvoiceContainer.visibility = View.GONE
        }

        // Token section - hide for Lightning payments with no token
        val tokenHeader: TextView = findViewById(R.id.token_header)
        val tokenText: TextView = findViewById(R.id.detail_token)
        val copyButton: Button = findViewById(R.id.btn_copy)
        val openWithButton: Button = findViewById(R.id.btn_open_with)
        
        if (entry.token.isEmpty() && paymentType == PaymentHistoryEntry.TYPE_LIGHTNING) {
            // Lightning payment without token
            tokenHeader.visibility = View.GONE
            tokenText.visibility = View.GONE
            copyButton.visibility = View.GONE
            openWithButton.visibility = View.GONE
        } else {
            tokenHeader.visibility = View.VISIBLE
            tokenText.visibility = View.VISIBLE
            tokenText.text = entry.token
            copyButton.visibility = View.VISIBLE
            openWithButton.visibility = View.VISIBLE
        }

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

    private fun getMintDisplayName(mintUrl: String): String {
        return MintManager.getInstance(this).getMintDisplayName(mintUrl)
    }

    private fun setupActionButtons() {
        val copyButton: Button = findViewById(R.id.btn_copy)
        val openWithButton: Button = findViewById(R.id.btn_open_with)
        val deleteButton: Button = findViewById(R.id.btn_delete)
        val viewBasketButton: Button = findViewById(R.id.btn_view_basket)

        copyButton.setOnClickListener { copyToken() }
        openWithButton.setOnClickListener { openWithApp() }
        deleteButton.setOnClickListener { showDeleteConfirmation() }

        // Always show View Receipt button - works with or without basket
        viewBasketButton.visibility = View.VISIBLE
        viewBasketButton.setOnClickListener { openBasketReceipt() }
    }

    private fun openBasketReceipt() {
        val intent = Intent(this, BasketReceiptActivity::class.java).apply {
            putExtra(BasketReceiptActivity.EXTRA_CHECKOUT_BASKET_JSON, checkoutBasketJson)
            putExtra(BasketReceiptActivity.EXTRA_PAYMENT_TYPE, paymentType)
            putExtra(BasketReceiptActivity.EXTRA_PAYMENT_DATE, entry.date.time)
            putExtra(BasketReceiptActivity.EXTRA_TRANSACTION_ID, entry.token.takeIf { it.isNotEmpty() }?.take(32))
            putExtra(BasketReceiptActivity.EXTRA_MINT_URL, entry.mintUrl)
            entry.bitcoinPrice?.let { putExtra(BasketReceiptActivity.EXTRA_BITCOIN_PRICE, it) }
            
            // For non-basket transactions, pass the payment amount info
            putExtra(BasketReceiptActivity.EXTRA_TOTAL_SATOSHIS, entry.amount)
            putExtra(BasketReceiptActivity.EXTRA_ENTERED_AMOUNT, entry.enteredAmount)
            putExtra(BasketReceiptActivity.EXTRA_ENTERED_CURRENCY, entry.getEntryUnit())
            
            // Pass tip information
            putExtra(BasketReceiptActivity.EXTRA_TIP_AMOUNT_SATS, entry.tipAmountSats)
            putExtra(BasketReceiptActivity.EXTRA_TIP_PERCENTAGE, entry.tipPercentage)
        }
        startActivity(intent)
    }

    private fun copyToken() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Cashu Token", entry.token)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Token copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun copyLightningInvoice() {
        val invoice = lightningInvoice ?: return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Lightning Invoice", invoice)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Invoice copied to clipboard", Toast.LENGTH_SHORT).show()
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

            val typeLabel = when (paymentType) {
                PaymentHistoryEntry.TYPE_LIGHTNING -> "Lightning"
                PaymentHistoryEntry.TYPE_CASHU -> "Cashu"
                else -> "Cashu"
            }

            val shareText = buildString {
                append("$typeLabel Payment\n")
                append("Amount: $amount\n")
                if (entry.token.isNotEmpty()) {
                    append("Token: ${entry.token}")
                } else if (!lightningInvoice.isNullOrEmpty()) {
                    append("Invoice: $lightningInvoice")
                }
            }

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
        const val EXTRA_TRANSACTION_PAYMENT_TYPE = "transaction_payment_type"
        const val EXTRA_TRANSACTION_LIGHTNING_INVOICE = "transaction_lightning_invoice"
        const val EXTRA_CHECKOUT_BASKET_JSON = "checkout_basket_json"
        const val EXTRA_TRANSACTION_TIP_AMOUNT = "transaction_tip_amount"
        const val EXTRA_TRANSACTION_TIP_PERCENTAGE = "transaction_tip_percentage"
    }
}
