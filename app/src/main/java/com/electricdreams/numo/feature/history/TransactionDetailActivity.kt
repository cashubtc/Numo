package com.electricdreams.numo.feature.history

import android.content.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.appcompat.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.electricdreams.numo.core.util.CurrencyManager
import com.electricdreams.numo.R
import com.electricdreams.numo.core.data.model.PaymentHistoryEntry
import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.core.model.CheckoutBasket
import com.electricdreams.numo.core.model.CheckoutBasketItem
import com.electricdreams.numo.core.model.SavedBasket
import com.electricdreams.numo.core.util.MintManager
import com.electricdreams.numo.feature.autowithdraw.AutoWithdrawManager
import com.electricdreams.numo.ui.util.DialogHelper
import com.electricdreams.numo.core.util.MintProfileService
import com.electricdreams.numo.core.util.SavedBasketManager
import com.electricdreams.numo.feature.enableEdgeToEdgeWithPill
import com.electricdreams.numo.ui.util.TransactionDates
import com.electricdreams.numo.ui.util.TransactionTransitions
import androidx.lifecycle.lifecycleScope
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.launch
import java.util.Date

/**
 * Full-screen activity to display detailed transaction information.
 */
class TransactionDetailActivity : AppCompatActivity() {

    private lateinit var entry: PaymentHistoryEntry
    private var paymentType: String? = null
    private var lightningInvoice: String? = null
    private var checkoutBasketJson: String? = null
    private var basketId: String? = null
    private var savedBasket: SavedBasket? = null
    private var tipAmountSats: Long = 0
    private var tipPercentage: Int = 0
    private var paymentId: String? = null
    private var currentLabel: String? = null
    private var status: String = PaymentHistoryEntry.STATUS_COMPLETED

    override fun onCreate(savedInstanceState: Bundle?) {
        TransactionTransitions.prepareDetail(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdgeWithPill(this)
        setContentView(R.layout.activity_transaction_detail)
        TransactionTransitions.attachDetail(this)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, insets.top, 0, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }

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
        paymentType = intent.getStringExtra(EXTRA_TRANSACTION_PAYMENT_TYPE)
        lightningInvoice = intent.getStringExtra(EXTRA_TRANSACTION_LIGHTNING_INVOICE)
        checkoutBasketJson = intent.getStringExtra(EXTRA_CHECKOUT_BASKET_JSON)
        basketId = intent.getStringExtra(EXTRA_BASKET_ID)
        tipAmountSats = intent.getLongExtra(EXTRA_TRANSACTION_TIP_AMOUNT, 0)
        tipPercentage = intent.getIntExtra(EXTRA_TRANSACTION_TIP_PERCENTAGE, 0)
        paymentId = intent.getStringExtra(EXTRA_TRANSACTION_ID)
        currentLabel = intent.getStringExtra(EXTRA_TRANSACTION_LABEL)
        status = intent.getStringExtra(EXTRA_TRANSACTION_STATUS) ?: PaymentHistoryEntry.STATUS_COMPLETED

        // Load saved basket if basketId is available
        basketId?.let { id ->
            savedBasket = SavedBasketManager.getInstance(this).getBasket(id)
        }

        // Create entry object
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

        setupViews()
    }

    private val isWithdrawal: Boolean
        get() = entry.amount < 0

    private fun setupViews() {
        val topBar = findViewById<com.electricdreams.numo.ui.components.NumoTopBar>(R.id.top_bar)
        // Back shrinks the details into their row, as system Back does
        topBar.onNavClick { finishAfterTransition() }
        topBar.onActionClick { showOverflowMenu(topBar.actionView) }

        // Display transaction details
        displayTransactionDetails()

        // Print Receipt button - only for settled incoming payments, not withdrawals
        val printReceiptBtn = findViewById<View>(R.id.btn_print_receipt)
        if (isWithdrawal || status != PaymentHistoryEntry.STATUS_COMPLETED) {
            printReceiptBtn.visibility = View.GONE
        } else {
            printReceiptBtn.setOnClickListener { printReceipt() }
        }
    }

    private fun showOverflowMenu(anchor: View) {
        val popup = PopupMenu(this, anchor, android.view.Gravity.END)
        popup.menuInflater.inflate(R.menu.menu_transaction_detail, popup.menu)
        popup.menu.findItem(R.id.menu_copy_id)?.isVisible = transactionId() != null

        // Style the delete item red to signal destructive action
        popup.menu.findItem(R.id.menu_delete_transaction)?.let { deleteItem ->
            val spannable = android.text.SpannableString(deleteItem.title)
            spannable.setSpan(
                android.text.style.ForegroundColorSpan(getColor(R.color.color_error)),
                0, spannable.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            deleteItem.title = spannable
        }

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_label_transaction -> {
                    showLabelDialog()
                    true
                }
                R.id.menu_copy_id -> {
                    copyTransactionId()
                    true
                }
                R.id.menu_delete_transaction -> {
                    showDeleteConfirmation()
                    true
                }
                else -> false
            }
        }

        popup.show()
    }

    private fun displayTransactionDetails() {
        val amountText: TextView = findViewById(R.id.detail_amount)
        val amountSubtitleText: TextView = findViewById(R.id.detail_amount_subtitle)
        val fiatEquivalentText: TextView = findViewById(R.id.detail_fiat_equivalent)

        // Use BASE amount (excluding tip) for display; use absolute value for withdrawals
        val entryUnit = entry.getEntryUnit()
        val tokenUnit = entry.getUnit()
        val lowerTokenUnit = tokenUnit.lowercase()
        val isCustomUnit = lowerTokenUnit != "sat"
        val baseAmountSats = kotlin.math.abs(entry.getBaseAmountSats())
        val charged: ChargedAmount

        if (isCustomUnit) {
            charged = ChargedAmount(formatInTokenUnit(baseAmountSats))
            amountText.text = charged.primary
            amountSubtitleText.visibility = View.GONE
            fiatEquivalentText.visibility = View.GONE
        } else {
            val baseSatAmount = Amount(baseAmountSats, Amount.Currency.BTC)
            // Determine what to show as primary vs secondary amount
            if (entry.enteredAmount > 0 && entry.getEntryUnit() != "sat") {
                // Fiat entry: show fiat large, sats subtitle, no fiat equivalent (already primary)
                val entryCurrency = Amount.Currency.fromCode(entry.getEntryUnit())
                val fiatAmount = Amount(entry.enteredAmount, entryCurrency)
                charged = ChargedAmount(fiatAmount.toString(), sats = baseSatAmount.toString())
                amountText.text = charged.primary
                amountSubtitleText.text = charged.sats
                amountSubtitleText.visibility = View.VISIBLE
                fiatEquivalentText.visibility = View.GONE
            } else {
                // Sat entry: show sats large, calculate fiat equivalent if exchange rate available
                charged = ChargedAmount(baseSatAmount.toString())
                amountText.text = charged.primary
                amountSubtitleText.visibility = View.GONE

                val btcPriceForFiat = entry.bitcoinPrice
                if (btcPriceForFiat != null && btcPriceForFiat > 0) {
                    val fiatValue = (baseAmountSats.toDouble() / 100_000_000.0) * btcPriceForFiat
                    val currencyCode = CurrencyManager.getInstance(this).getCurrentCurrency()
                    val fiatCurrency = Amount.Currency.fromCode(currencyCode)
                    val fiatMinorUnits = kotlin.math.round(fiatValue * 100).toLong()
                    val fiatAmount = Amount(fiatMinorUnits, fiatCurrency)
                    fiatEquivalentText.text = "$fiatAmount"
                    fiatEquivalentText.visibility = View.VISIBLE
                } else {
                    fiatEquivalentText.visibility = View.GONE
                }
            }
        }

        // Date, in the device's own format and 12/24-hour setting
        val dateText: TextView = findViewById(R.id.detail_date)
        dateText.text = TransactionDates.full(this, entry.date)

        // Status, only while the payment isn't settled
        val statusText: TextView = findViewById(R.id.detail_status)
        val (statusRes, statusColor) = when (status) {
            PaymentHistoryEntry.STATUS_PENDING -> R.string.payment_history_status_pending to R.color.color_warning
            PaymentHistoryEntry.STATUS_EXPIRED -> R.string.history_row_status_expired to R.color.color_error
            PaymentHistoryEntry.STATUS_FAILED -> R.string.history_row_status_failed to R.color.color_error
            else -> null to null
        }
        if (statusRes != null && statusColor != null) {
            statusText.setText(statusRes)
            statusText.setTextColor(getColor(statusColor))
            statusText.visibility = View.VISIBLE
        } else {
            statusText.visibility = View.GONE
        }

        // Payment Type: only for a settled payment. One that never went through has no type, and
        // "Payment received" under "Expired" would contradict it.
        val isSettled = status == PaymentHistoryEntry.STATUS_COMPLETED
        val paymentTypeText: TextView = findViewById(R.id.detail_payment_type)
        paymentTypeText.text = when {
            isWithdrawal -> getString(R.string.transaction_detail_type_withdrawal)
            paymentType == PaymentHistoryEntry.TYPE_LIGHTNING -> getString(R.string.transaction_detail_payment_type_lightning_value)
            paymentType == PaymentHistoryEntry.TYPE_CASHU -> getString(R.string.transaction_detail_payment_type_cashu_value)
            else -> getString(R.string.transaction_detail_type_payment_received)
        }
        val hasType = isWithdrawal || paymentType == PaymentHistoryEntry.TYPE_LIGHTNING ||
            paymentType == PaymentHistoryEntry.TYPE_CASHU
        findViewById<View>(R.id.row_transaction_type).visibility =
            if (hasType || isSettled) View.VISIBLE else View.GONE

        // Exchange Rate
        val exchangeRateRow: View = findViewById(R.id.row_exchange_rate)
        val exchangeRateText: TextView = findViewById(R.id.detail_exchange_rate)

        val btcPrice = entry.bitcoinPrice
        if (btcPrice != null && btcPrice > 0) {
            val currencyCode = if (entry.getEntryUnit() != "sat" && entry.getEntryUnit() != "BTC") {
                entry.getEntryUnit()
            } else {
                val basket = CheckoutBasket.fromJson(checkoutBasketJson)
                basket?.currency ?: CurrencyManager.getInstance(this).getCurrentCurrency()
            }

            val currency = Amount.Currency.fromCode(currencyCode)
            val priceCurrency = if (currency == Amount.Currency.BTC) {
                Amount.Currency.fromCode(CurrencyManager.getInstance(this).getCurrentCurrency())
            } else {
                currency
            }

            val priceMinorUnits = kotlin.math.round(btcPrice * 100).toLong()
            val formattedPrice = Amount(priceMinorUnits, priceCurrency).toString()

            exchangeRateText.text = formattedPrice
            exchangeRateRow.visibility = View.VISIBLE
        } else {
            exchangeRateRow.visibility = View.GONE
        }

        // Tip, on its own row: tips are never part of the sale amount
        val tipRow: View = findViewById(R.id.row_tip)
        val tip = formatTip()
        if (tip != null) {
            findViewById<TextView>(R.id.detail_tip).text = tip
            tipRow.visibility = View.VISIBLE
        } else {
            tipRow.visibility = View.GONE
        }

        // Mint display name (falls back to URL host if no name stored)
        val mintUrlText: TextView = findViewById(R.id.detail_mint_url)
        val primaryMintUrl = entry.mintUrl ?: entry.lightningMintUrl

        if (!primaryMintUrl.isNullOrEmpty()) {
            val displayName = getMintDisplayName(primaryMintUrl)
            mintUrlText.text = displayName

            // If no cached mint info, try fetching it in the background and update
            val mintManager = MintManager.getInstance(this)
            if (mintManager.getMintInfo(primaryMintUrl) == null) {
                lifecycleScope.launch {
                    try {
                        MintProfileService.getInstance(this@TransactionDetailActivity)
                            .fetchAndStoreMintProfile(primaryMintUrl)
                        // Re-read display name after fetch
                        val updatedName = getMintDisplayName(primaryMintUrl)
                        if (updatedName != displayName) {
                            mintUrlText.text = updatedName
                        }
                    } catch (_: Exception) {
                        // Keep the URL-based fallback
                    }
                }
            }
        } else {
            mintUrlText.text = getString(R.string.transaction_detail_mint_unknown)
        }
        // No mint yet is expected until a payment settles; "Unknown" is only worth saying after
        findViewById<View>(R.id.row_mint).visibility =
            if (!primaryMintUrl.isNullOrEmpty() || isSettled) View.VISIBLE else View.GONE

        // Sent To row (withdrawals only)
        val sentToRow: View = findViewById(R.id.row_sent_to)
        val sentToText: TextView = findViewById(R.id.detail_sent_to)
        val copyDestinationBtn: ImageButton = findViewById(R.id.btn_copy_destination)

        val destination = entry.paymentRequest
        if (isWithdrawal && !destination.isNullOrEmpty()) {
            sentToText.text = destination
            copyDestinationBtn.setOnClickListener { copyDestination(destination) }
            sentToRow.visibility = View.VISIBLE
        } else {
            sentToRow.visibility = View.GONE
        }

        // Invoice / Transaction ID row
        val invoiceRow: View = findViewById(R.id.row_invoice)
        val invoiceLabel: TextView = findViewById(R.id.detail_invoice_label)
        val invoiceValue: TextView = findViewById(R.id.detail_invoice_value)
        val copyInvoiceBtn: ImageButton = findViewById(R.id.btn_copy_invoice)

        val isCashu = paymentType == PaymentHistoryEntry.TYPE_CASHU

        val invoice = lightningInvoice
        if (!invoice.isNullOrEmpty()) {
            invoiceLabel.text = if (isCashu) {
                getString(R.string.transaction_detail_cashu_request_label)
            } else {
                getString(R.string.transaction_detail_invoice_label)
            }
            invoiceValue.text = formatTruncatedId(invoice)
            copyInvoiceBtn.setOnClickListener { copyLightningInvoice() }
            invoiceRow.visibility = View.VISIBLE
        } else if (entry.token.isNotEmpty()) {
            invoiceLabel.text = if (isCashu) {
                getString(R.string.transaction_detail_cashu_request_label)
            } else {
                getString(R.string.transaction_detail_transaction_id_label)
            }
            invoiceValue.text = formatTruncatedId(entry.token)
            copyInvoiceBtn.setOnClickListener { copyToken() }
            invoiceRow.visibility = View.VISIBLE
        } else {
            invoiceRow.visibility = View.GONE
        }

        // Label row
        updateLabelRow()
        
        // Items and Totals
        displayItemsAndTotals(charged)
    }

    /** The sale before tip as the headline shows it, with its sats when it was charged in fiat */
    private data class ChargedAmount(val primary: String, val sats: String? = null)

    /** An amount in a mint's own unit (e.g. "usd" ecash), which has no sats behind it */
    private fun formatInTokenUnit(value: Long): String {
        val tokenUnit = entry.getUnit().lowercase()
        val currency = Amount.Currency.fromCode(tokenUnit)
        return if (currency.symbol != tokenUnit.uppercase()) {
            Amount(if (currency.isZeroDecimal()) value * 100 else value, currency).toString()
        } else {
            "$value ${tokenUnit.uppercase()}"
        }
    }

    /**
     * The tip, in the currency the sale was keyed in and valued at the sale's own price, with
     * its percentage; null when there was none.
     */
    private fun formatTip(): String? {
        val tipSats = entry.tipAmountSats
        if (tipSats <= 0) return null
        val price = entry.bitcoinPrice?.takeIf { it > 0 }
        val entryUnit = entry.getEntryUnit()
        val amount = when {
            !entry.getUnit().equals("sat", ignoreCase = true) -> formatInTokenUnit(tipSats)
            price != null && !entryUnit.equals("sat", ignoreCase = true) -> {
                val minor = kotlin.math.round(tipSats / 100_000_000.0 * price * 100).toLong()
                Amount(minor, Amount.Currency.fromCode(entryUnit)).toString()
            }
            else -> Amount(tipSats, Amount.Currency.BTC).toString()
        }
        return if (entry.tipPercentage > 0) {
            getString(R.string.transaction_detail_tip_value, amount, entry.tipPercentage)
        } else {
            amount
        }
    }

    private fun displayItemsAndTotals(charged: ChargedAmount) {
        // Use saved basket data if available, otherwise fall back to checkoutBasketJson
        val basketJsonToUse = if (savedBasket != null) {
            convertSavedBasketToCheckoutBasket(savedBasket!!).toJson()
        } else {
            checkoutBasketJson
        }

        val basket = CheckoutBasket.fromJson(basketJsonToUse)
        if (basket == null || basket.items.isEmpty()) {
            return
        }

        val enteredCurrency = entry.getEntryUnit() ?: com.electricdreams.numo.core.util.CurrencyManager.getInstance(this).getCurrentCurrency()
        val currency = Amount.Currency.fromCode(basket.currency ?: enteredCurrency)
        
        // 1. Setup Views
        val itemsHeader: TextView = findViewById(R.id.items_header)
        val itemsContainer: LinearLayout = findViewById(R.id.items_container)
        val totalsContainer: LinearLayout = findViewById(R.id.totals_container)
        val subtotalRow: LinearLayout = findViewById(R.id.subtotal_row)
        val subtotalLabel: TextView = findViewById(R.id.subtotal_label)
        val subtotalValue: TextView = findViewById(R.id.subtotal_value)
        val vatBreakdownContainer: LinearLayout = findViewById(R.id.vat_breakdown_container)
        val satsItemsRow: LinearLayout = findViewById(R.id.sats_items_row)
        val satsItemsValue: TextView = findViewById(R.id.sats_items_value)
        val satsItemsEquiv: TextView = findViewById(R.id.sats_items_equiv)
        val finalTotalValue: TextView = findViewById(R.id.final_total_value)
        val satsEquivalentText: TextView = findViewById(R.id.sats_equivalent)

        itemsHeader.visibility = View.VISIBLE
        itemsContainer.visibility = View.VISIBLE
        totalsContainer.visibility = View.VISIBLE

        // 2. Display Items
        itemsContainer.removeAllViews()
        val itemCount = basket.getTotalItemCount()
        itemsHeader.text = if (itemCount == 1) getString(R.string.basket_receipt_items_header_single) else getString(R.string.basket_receipt_items_header_multiple, itemCount)

        val inflater = android.view.LayoutInflater.from(this)
        basket.items.forEachIndexed { index, item ->
            val itemView = inflater.inflate(R.layout.item_receipt_line, itemsContainer, false)
            
            val qtyText = itemView.findViewById<TextView>(R.id.item_quantity)
            val nameText = itemView.findViewById<TextView>(R.id.item_name)
            val unitPriceText = itemView.findViewById<TextView>(R.id.item_unit_price)
            val priceText = itemView.findViewById<TextView>(R.id.item_total)
            
            qtyText.text = item.quantity.toString()
            nameText.text = item.displayName
            
            if (item.isFiatPrice()) {
                val unitPrice = Amount(item.getGrossPricePerUnitCents(), currency)
                unitPriceText.text = if (item.quantity > 1) getString(R.string.basket_receipt_item_each, unitPrice.toString()) else unitPrice.toString()
                
                val lineTotal = Amount(item.getGrossTotalCents(), currency)
                priceText.text = lineTotal.toString()
            } else {
                val unitPrice = Amount(item.priceSats, Amount.Currency.BTC)
                unitPriceText.text = if (item.quantity > 1) getString(R.string.basket_receipt_item_each, unitPrice.toString()) else unitPrice.toString()
                
                val directTotal = item.quantity * item.priceSats
                priceText.text = Amount(directTotal, Amount.Currency.BTC).toString()
            }

            itemsContainer.addView(itemView)

            if (index < basket.items.size - 1) {
                val divider = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        1 // 1px height
                    ).apply {
                        setMargins(
                            (16 * resources.displayMetrics.density).toInt(),
                            (8 * resources.displayMetrics.density).toInt(),
                            (16 * resources.displayMetrics.density).toInt(),
                            (8 * resources.displayMetrics.density).toInt()
                        )
                    }
                    setBackgroundColor(resources.getColor(R.color.color_divider, theme))
                }
                itemsContainer.addView(divider)
            }
        }

        // 3. Display Totals
        val hasVat = basket.hasVat()
        val hasFiatItems = basket.getFiatItems().isNotEmpty()
        val hasSatsItems = basket.getSatsItems().isNotEmpty()

        if (hasVat && hasFiatItems) {
            val netTotal = basket.getFiatNetTotalCents()
            subtotalLabel.text = getString(R.string.basket_receipt_fiat_subtotal_net_label)
            subtotalValue.text = Amount(netTotal, currency).toString()
            subtotalRow.visibility = View.VISIBLE
        } else if (hasFiatItems && hasSatsItems) {
            subtotalLabel.text = getString(R.string.basket_receipt_fiat_items_label)
            subtotalValue.text = Amount(basket.getFiatGrossTotalCents(), currency).toString()
            subtotalRow.visibility = View.VISIBLE
        } else {
            subtotalRow.visibility = View.GONE
        }

        vatBreakdownContainer.removeAllViews()
        if (hasVat && hasFiatItems) {
            val vatBreakdown = basket.getVatBreakdown()
            vatBreakdown.forEach { (rate, amountCents) ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = (8 * resources.displayMetrics.density).toInt()
                    }
                }

                val label = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    text = getString(R.string.basket_receipt_vat_row_label, rate)
                    textSize = 15f
                    setTextColor(resources.getColor(R.color.color_text_secondary, theme))
                }

                val value = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    text = Amount(amountCents, currency).toString()
                    textSize = 15f
                    setTextColor(resources.getColor(R.color.color_text_secondary, theme))
                }

                row.addView(label)
                row.addView(value)
                vatBreakdownContainer.addView(row)
            }
        }
        
        if (hasSatsItems) {
            satsItemsValue.text = Amount(basket.getSatsDirectTotal(), Amount.Currency.BTC).toString()
            
            if (entry.bitcoinPrice != null && entry.bitcoinPrice!! > 0) {
                val satsInFiat = ((basket.getSatsDirectTotal().toDouble() / 100_000_000.0) * entry.bitcoinPrice!! * 100).toLong()
                satsItemsEquiv.text = "≈ ${Amount(satsInFiat, currency)}"
                satsItemsEquiv.visibility = View.VISIBLE
            } else {
                satsItemsEquiv.visibility = View.GONE
            }
            satsItemsRow.visibility = View.VISIBLE
        } else {
            satsItemsRow.visibility = View.GONE
        }

        // The same figure as the headline, so the two can't disagree
        finalTotalValue.text = charged.primary
        satsEquivalentText.text = charged.sats?.let { "≈ $it" }
        satsEquivalentText.visibility = if (charged.sats != null) View.VISIBLE else View.GONE
        if (!entry.getUnit().equals("sat", ignoreCase = true)) {
            satsItemsRow.visibility = View.GONE
        }

        // With nothing itemised above it, the total stands alone: no gap, no rule
        val hasBreakdown = subtotalRow.visibility == View.VISIBLE ||
            vatBreakdownContainer.childCount > 0 ||
            satsItemsRow.visibility == View.VISIBLE
        vatBreakdownContainer.visibility = if (hasBreakdown) View.VISIBLE else View.GONE
        findViewById<View>(R.id.totals_divider).visibility =
            if (hasBreakdown) View.VISIBLE else View.GONE
    }

    /**
     * Truncates an ID to first 6 + "…" + middle 6 + "…" + last 6 characters.
     */
    private fun formatTruncatedId(id: String): String {
        if (id.length <= 21) return id

        val midStart = (id.length / 2) - 3
        val first = id.take(6)
        val middle = id.substring(midStart, midStart + 6)
        val last = id.takeLast(6)
        return "$first\u2026$middle\u2026$last"
    }

    private fun getMintDisplayName(mintUrl: String): String {
        return MintManager.getInstance(this).getMintDisplayName(mintUrl)
    }

    private fun printReceipt() {
        // Use saved basket data if available, otherwise fall back to checkoutBasketJson
        val basketJsonToUse = if (savedBasket != null) {
            convertSavedBasketToCheckoutBasket(savedBasket!!).toJson()
        } else {
            checkoutBasketJson
        }

        val basket = CheckoutBasket.fromJson(basketJsonToUse)
        val receiptPrinter = com.electricdreams.numo.core.util.ReceiptPrinter(this)
        
        val txId = entry.token.takeIf { it.isNotEmpty() } ?: lightningInvoice
        val enteredCurrency = entry.getEntryUnit() ?: com.electricdreams.numo.core.util.CurrencyManager.getInstance(this).getCurrentCurrency()

        val receiptData = com.electricdreams.numo.core.util.ReceiptPrinter.ReceiptData(
            basket = basket,
            paymentType = paymentType,
            paymentDate = entry.date,
            transactionId = txId,
            mintUrl = entry.mintUrl,
            bitcoinPrice = entry.bitcoinPrice,
            totalSatoshis = entry.amount,
            enteredAmount = entry.enteredAmount,
            enteredCurrency = enteredCurrency,
            tipAmountSats = entry.tipAmountSats,
            tipPercentage = entry.tipPercentage
        )
        receiptPrinter.printReceipt(receiptData)
    }

    private fun convertSavedBasketToCheckoutBasket(savedBasket: SavedBasket): CheckoutBasket {
        val checkoutItems = savedBasket.items.map { basketItem ->
            CheckoutBasketItem.fromBasketItem(
                basketItem = basketItem,
                currencyCode = CurrencyManager.getInstance(this).getCurrentCurrency(),
            )
        }

        val currency = CurrencyManager
            .getInstance(this)
            .getCurrentCurrency()

        return CheckoutBasket(
            id = savedBasket.id,
            checkoutTimestamp = savedBasket.paidAt ?: savedBasket.updatedAt,
            items = checkoutItems,
            currency = currency,
            bitcoinPrice = entry.bitcoinPrice,
            totalSatoshis = entry.amount,
        )
    }

    private fun copyToken() {
        copyToClipboard("Cashu Token", entry.token, R.string.history_toast_token_copied)
    }

    private fun copyLightningInvoice() {
        val invoice = lightningInvoice ?: return
        copyToClipboard("Lightning Invoice", invoice, R.string.history_toast_invoice_copied)
    }

    private fun copyDestination(destination: String) {
        copyToClipboard("Destination", destination, R.string.transaction_detail_destination_copied)
    }

    private fun copyTransactionId() {
        val id = transactionId() ?: return
        copyToClipboard("Transaction ID", id, R.string.transaction_detail_toast_id_copied)
    }

    /** The invoice or token this payment is known by; null when it has neither */
    private fun transactionId(): String? =
        lightningInvoice?.takeIf { it.isNotEmpty() } ?: entry.token.takeIf { it.isNotEmpty() }

    // Android 13+ confirms every copy itself; a toast on top would say it twice
    private fun copyToClipboard(label: String, text: String, toastRes: Int) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, getString(toastRes), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showLabelDialog() {
        DialogHelper.showInput(this, DialogHelper.InputConfig(
            title = getString(R.string.transaction_detail_label_dialog_title),
            hint = getString(R.string.transaction_detail_label_dialog_hint),
            initialValue = currentLabel ?: "",
            saveText = getString(R.string.transaction_detail_label_dialog_save),
            onSave = { label ->
                currentLabel = label.ifBlank { null }
                paymentId?.let { id ->
                    PaymentsHistoryActivity.updateLabel(this, id, currentLabel)
                    AutoWithdrawManager.getInstance(this).updateWithdrawalLabel(id, currentLabel)
                }
                updateLabelRow()
            }
        ))
    }

    private fun updateLabelRow() {
        val labelRow: View = findViewById(R.id.row_label)
        val labelValue: TextView = findViewById(R.id.detail_label_value)

        if (!currentLabel.isNullOrBlank()) {
            labelValue.text = currentLabel
            labelRow.visibility = View.VISIBLE
        } else {
            labelRow.visibility = View.GONE
        }
    }

    private fun showDeleteConfirmation() {
        DialogHelper.showConfirmation(this, DialogHelper.ConfirmationConfig(
            title = getString(R.string.history_dialog_delete_title),
            message = getString(R.string.history_dialog_delete_message),
            confirmText = getString(R.string.history_dialog_delete_positive),
            isDestructive = true,
            onConfirm = { deleteTransaction() }
        ))
    }

    // By id, so a delete works wherever the details were opened from (Activity, Sales, a receipt)
    private fun deleteTransaction() {
        paymentId?.let { id ->
            PaymentsHistoryActivity.deletePayment(this, id)
            AutoWithdrawManager.getInstance(this).deleteHistoryEntry(id)
        }
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
        const val EXTRA_TRANSACTION_PAYMENT_TYPE = "transaction_payment_type"
        const val EXTRA_TRANSACTION_LIGHTNING_INVOICE = "transaction_lightning_invoice"
        const val EXTRA_CHECKOUT_BASKET_JSON = "checkout_basket_json"
        const val EXTRA_BASKET_ID = "basket_id"
        const val EXTRA_TRANSACTION_TIP_AMOUNT = "transaction_tip_amount"
        const val EXTRA_TRANSACTION_TIP_PERCENTAGE = "transaction_tip_percentage"
        const val EXTRA_TRANSACTION_ID = "transaction_id"
        const val EXTRA_TRANSACTION_LABEL = "transaction_label"
        const val EXTRA_TRANSACTION_STATUS = "transaction_status"
    }
}
