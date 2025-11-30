package com.electricdreams.numo.feature.history

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.electricdreams.numo.R
import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.core.model.CheckoutBasket
import com.electricdreams.numo.core.model.CheckoutBasketItem
import com.electricdreams.numo.core.util.ReceiptPrinter
import com.electricdreams.numo.feature.enableEdgeToEdgeWithPill
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Beautiful receipt view displaying all items purchased in a checkout.
 * Follows Apple-like design principles with clean typography,
 * generous spacing, and professional layout suitable for baristas
 * to verify customer orders.
 * 
 * Supports three display modes:
 * 1. Fiat-only basket: Large fiat amount, grey sats below
 * 2. Mixed/Sats basket: Large sats amount, grey fiat equivalent below
 * 3. No basket: Single "Payment" line item with amount
 */
class BasketReceiptActivity : AppCompatActivity() {

    private lateinit var totalAmountText: TextView
    private lateinit var totalSubtitleText: TextView
    private lateinit var checkoutDateText: TextView
    private lateinit var itemsHeaderText: TextView
    private lateinit var itemsContainer: LinearLayout
    private lateinit var totalsContainer: LinearLayout
    private lateinit var subtotalRow: LinearLayout
    private lateinit var subtotalLabel: TextView
    private lateinit var subtotalValue: TextView
    private lateinit var vatBreakdownContainer: LinearLayout
    private lateinit var satsItemsRow: LinearLayout
    private lateinit var satsItemsValue: TextView
    private lateinit var satsItemsEquiv: TextView
    private lateinit var finalTotalLabel: TextView
    private lateinit var finalTotalValue: TextView
    private lateinit var satsEquivalentText: TextView
    private lateinit var paidAmountText: TextView
    private lateinit var printButton: ImageButton

    private var basket: CheckoutBasket? = null
    
    // Additional payment data for printing and display
    private var paymentType: String? = null
    private var paymentDate: Date = Date()
    private var transactionId: String? = null
    private var mintUrl: String? = null
    private var bitcoinPrice: Double? = null
    
    // For non-basket transactions
    private var totalSatoshis: Long = 0
    private var enteredAmount: Long = 0
    private var enteredCurrency: String = "USD"
    
    // Tip information
    private var tipAmountSats: Long = 0
    private var tipPercentage: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_basket_receipt)

        // Edge-to-edge so the receipt sheet scrolls behind the gesture nav pill
        enableEdgeToEdgeWithPill(this, lightNavIcons = true)

        initializeViews()
        loadBasketData()
        displayReceipt()
    }

    private fun initializeViews() {
        // Back button
        findViewById<ImageButton>(R.id.back_button).setOnClickListener { finish() }
        
        // Print button
        printButton = findViewById(R.id.print_button)
        printButton.setOnClickListener { printReceipt() }

        // Hero section
        totalAmountText = findViewById(R.id.total_amount)
        totalSubtitleText = findViewById(R.id.total_subtitle)
        checkoutDateText = findViewById(R.id.checkout_date)

        // Items section
        itemsHeaderText = findViewById(R.id.items_header)
        itemsContainer = findViewById(R.id.items_container)

        // Totals section
        totalsContainer = findViewById(R.id.totals_container)
        subtotalRow = findViewById(R.id.subtotal_row)
        subtotalLabel = findViewById(R.id.subtotal_label)
        subtotalValue = findViewById(R.id.subtotal_value)
        vatBreakdownContainer = findViewById(R.id.vat_breakdown_container)
        satsItemsRow = findViewById(R.id.sats_items_row)
        satsItemsValue = findViewById(R.id.sats_items_value)
        satsItemsEquiv = findViewById(R.id.sats_items_equiv)
        finalTotalLabel = findViewById(R.id.final_total_label)
        finalTotalValue = findViewById(R.id.final_total_value)
        satsEquivalentText = findViewById(R.id.sats_equivalent)

        // Payment info
        paidAmountText = findViewById(R.id.paid_amount)
    }

    private fun loadBasketData() {
        val basketJson = intent.getStringExtra(EXTRA_CHECKOUT_BASKET_JSON)
        basket = CheckoutBasket.fromJson(basketJson)
        
        // Load additional payment data
        paymentType = intent.getStringExtra(EXTRA_PAYMENT_TYPE)
        val dateMillis = intent.getLongExtra(EXTRA_PAYMENT_DATE, System.currentTimeMillis())
        paymentDate = Date(dateMillis)
        transactionId = intent.getStringExtra(EXTRA_TRANSACTION_ID)
        mintUrl = intent.getStringExtra(EXTRA_MINT_URL)
        val btcPrice = intent.getDoubleExtra(EXTRA_BITCOIN_PRICE, -1.0)
        bitcoinPrice = if (btcPrice > 0) btcPrice else null
        
        // For non-basket transactions
        totalSatoshis = intent.getLongExtra(EXTRA_TOTAL_SATOSHIS, 0)
        enteredAmount = intent.getLongExtra(EXTRA_ENTERED_AMOUNT, 0)
        enteredCurrency = intent.getStringExtra(EXTRA_ENTERED_CURRENCY) ?: "USD"
        
        // Load tip information
        tipAmountSats = intent.getLongExtra(EXTRA_TIP_AMOUNT_SATS, 0)
        tipPercentage = intent.getIntExtra(EXTRA_TIP_PERCENTAGE, 0)
        
        android.util.Log.d("BasketReceiptActivity", "Received basket JSON: ${basketJson?.length ?: 0} chars")
        android.util.Log.d("BasketReceiptActivity", "Parsed basket: ${basket?.items?.size ?: 0} items")
        android.util.Log.d("BasketReceiptActivity", "Non-basket fallback: totalSats=$totalSatoshis, enteredAmount=$enteredAmount, currency=$enteredCurrency")
        android.util.Log.d("BasketReceiptActivity", "Tip: ${tipAmountSats} sats ($tipPercentage%)")
    }
    
    private fun printReceipt() {
        val receiptPrinter = ReceiptPrinter(this)
        val receiptData = ReceiptPrinter.ReceiptData(
            basket = basket,
            paymentType = paymentType,
            paymentDate = paymentDate,
            transactionId = transactionId,
            mintUrl = mintUrl,
            bitcoinPrice = bitcoinPrice,
            totalSatoshis = basket?.totalSatoshis ?: totalSatoshis,
            enteredAmount = enteredAmount,
            enteredCurrency = enteredCurrency,
            tipAmountSats = tipAmountSats,
            tipPercentage = tipPercentage,
        )
        
        // Print directly - one click printing
        receiptPrinter.printReceipt(receiptData)
    }
    
    /**
     * Determine if sats should be the primary display amount.
     * True for: mixed baskets, sats-only baskets, or sats-only payments
     */
    private fun shouldShowSatsAsPrimary(): Boolean {
        val b = basket
        if (b != null) {
            // Mixed pricing or sats-only = show sats as primary
            return b.hasMixedPriceTypes() || b.getFiatItems().isEmpty()
        }
        // No basket - use fiat if entered amount exists, otherwise sats
        return enteredAmount == 0L && enteredCurrency == "sat"
    }
    
    /**
     * Calculate total fiat value including converted sats items.
     */
    private fun getTotalFiatIncludingSatsConversion(): Long {
        val b = basket
        if (b == null) {
            return enteredAmount
        }
        
        val fiatTotal = b.getFiatGrossTotalCents()
        val satsItems = b.getSatsItems()
        
        if (satsItems.isEmpty() || bitcoinPrice == null || bitcoinPrice!! <= 0) {
            return fiatTotal
        }
        
        // Convert sats items to fiat
        val satsTotal = b.getSatsDirectTotal()
        val satsInFiat = ((satsTotal.toDouble() / 100_000_000.0) * bitcoinPrice!! * 100).toLong()
        
        return fiatTotal + satsInFiat
    }

    private fun displayReceipt() {
        // Display hero section
        displayHeroSection()

        // Display items
        displayItems()

        // Display totals
        displayTotals()

        // Display payment info
        displayPaymentInfo()
    }

    private fun displayHeroSection() {
        val b = basket
        val currency = b?.let { Amount.Currency.fromCode(it.currency) } 
            ?: Amount.Currency.fromCode(enteredCurrency)
        
        val showSatsAsPrimary = shouldShowSatsAsPrimary()
        
        // Use BASE amounts (excluding tip) for the hero section - this is what was sold
        val baseSats = (b?.totalSatoshis ?: totalSatoshis) - tipAmountSats
        val baseFiat = getTotalFiatIncludingSatsConversion() // enteredAmount is already base amount
        
        if (showSatsAsPrimary) {
            // Primary: Sats amount (base, not including tip)
            val satsAmount = Amount(baseSats, Amount.Currency.BTC)
            totalAmountText.text = satsAmount.toString()
            
            // Secondary: Fiat equivalent
            if (baseFiat > 0) {
                val fiatEquiv = Amount(baseFiat, currency)
                totalSubtitleText.text = "≈ $fiatEquiv"
                totalSubtitleText.visibility = View.VISIBLE
            } else {
                totalSubtitleText.visibility = View.GONE
            }
        } else {
            // Primary: Fiat amount (entered amount is already base amount)
            val fiatAmount = Amount(baseFiat, currency)
            totalAmountText.text = fiatAmount.toString()
            
            // Secondary: Sats paid (base amount)
            if (baseSats > 0) {
                val satsAmount = Amount(baseSats, Amount.Currency.BTC)
                totalSubtitleText.text = satsAmount.toString()
                totalSubtitleText.setTextColor(resources.getColor(R.color.color_text_tertiary, theme))
                totalSubtitleText.visibility = View.VISIBLE
            } else {
                totalSubtitleText.visibility = View.GONE
            }
        }

        // VAT subtitle (if applicable and we have a basket with VAT)
        val totalVat = b?.getFiatVatTotalCents() ?: 0
        if (totalVat > 0) {
            val vatAmount = Amount(totalVat, currency)
            // Add VAT info to subtitle if space permits
            val currentSubtitle = totalSubtitleText.text.toString()
            if (currentSubtitle.isNotEmpty()) {
                totalSubtitleText.text = getString(
                    R.string.basket_receipt_total_subtitle_with_vat,
                    currentSubtitle,
                    vatAmount.toString()
                )
            } else {
                totalSubtitleText.text = getString(R.string.basket_receipt_total_subtitle_vat_only, vatAmount.toString())
                totalSubtitleText.visibility = View.VISIBLE
            }
        }

        // Checkout date
        val dateFormat = SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.getDefault())
        checkoutDateText.text = dateFormat.format(paymentDate)
    }

    private fun displayItems() {
        val b = basket
        val currency = b?.let { Amount.Currency.fromCode(it.currency) } 
            ?: Amount.Currency.fromCode(enteredCurrency)
        
        // Clear container
        itemsContainer.removeAllViews()
        
        if (b != null && b.items.isNotEmpty()) {
            // Items header with count
            val itemCount = b.getTotalItemCount()
            itemsHeaderText.text = if (itemCount == 1) getString(R.string.basket_receipt_items_header_single) else getString(R.string.basket_receipt_items_header_multiple, itemCount)

            // Add each item
            val inflater = LayoutInflater.from(this)

            b.items.forEachIndexed { index, item ->
                val itemView = inflater.inflate(R.layout.item_receipt_line, itemsContainer, false)
                bindItemView(itemView, item, currency)
                itemsContainer.addView(itemView)

                // Add divider between items (not after last)
                if (index < b.items.size - 1) {
                    addDivider(itemsContainer)
                }
            }
        } else {
            // No basket - single "Payment" line
            itemsHeaderText.text = getString(R.string.basket_receipt_items_header_single)
            
            val inflater = LayoutInflater.from(this)
            val itemView = inflater.inflate(R.layout.item_receipt_line, itemsContainer, false)
            bindPaymentOnlyView(itemView, currency)
            itemsContainer.addView(itemView)
        }
    }

    private fun bindItemView(view: View, item: CheckoutBasketItem, currency: Amount.Currency) {
        // Quantity badge
        val quantityText = view.findViewById<TextView>(R.id.item_quantity)
        quantityText.text = item.quantity.toString()

        // Item name (with variation if present)
        val nameText = view.findViewById<TextView>(R.id.item_name)
        nameText.text = item.displayName

        // Unit price text and line total - show in original currency
        val unitPriceText = view.findViewById<TextView>(R.id.item_unit_price)
        val totalText = view.findViewById<TextView>(R.id.item_total)
        val vatDetailRow = view.findViewById<LinearLayout>(R.id.vat_detail_row)
        
        if (item.isFiatPrice()) {
            val unitPrice = Amount(item.getGrossPricePerUnitCents(), currency)
            unitPriceText.text = if (item.quantity > 1) "$unitPrice each" else "$unitPrice"
            
            val lineTotal = Amount(item.getGrossTotalCents(), currency)
            totalText.text = lineTotal.toString()
            
            // VAT detail row (only for fiat items with VAT)
            if (item.vatEnabled && item.vatRate > 0) {
                val vatLabel = view.findViewById<TextView>(R.id.vat_label)
                val vatAmountText = view.findViewById<TextView>(R.id.vat_amount)

                vatLabel.text = getString(R.string.basket_receipt_vat_label, item.vatRate)
                val itemVat = Amount(item.getTotalVatCents(), currency)
                vatAmountText.text = itemVat.toString()
                vatDetailRow.visibility = View.VISIBLE
            } else {
                vatDetailRow.visibility = View.GONE
            }
        } else {
            // Sats-priced item
            val unitPriceSats = Amount(item.priceSats, Amount.Currency.BTC)
            unitPriceText.text = if (item.quantity > 1) "$unitPriceSats each" else "$unitPriceSats"
            
            val lineTotalSats = Amount(item.getNetTotalSats(), Amount.Currency.BTC)
            totalText.text = lineTotalSats.toString()
            
            // Show fiat equivalent in VAT row
            if (bitcoinPrice != null && bitcoinPrice!! > 0) {
                val satsInFiat = ((item.getNetTotalSats().toDouble() / 100_000_000.0) * bitcoinPrice!! * 100).toLong()
                val fiatEquiv = Amount(satsInFiat, currency)
                
                val vatLabel = view.findViewById<TextView>(R.id.vat_label)
                val vatAmountText = view.findViewById<TextView>(R.id.vat_amount)
                vatLabel.text = getString(R.string.basket_receipt_equivalent_label)
                vatAmountText.text = "≈ $fiatEquiv"
                vatDetailRow.visibility = View.VISIBLE
            } else {
                vatDetailRow.visibility = View.GONE
            }
        }
    }
    
    private fun bindPaymentOnlyView(view: View, currency: Amount.Currency) {
        // Quantity badge
        val quantityText = view.findViewById<TextView>(R.id.item_quantity)
        quantityText.text = "1"

        // Item name
        val nameText = view.findViewById<TextView>(R.id.item_name)
        nameText.text = getString(R.string.basket_receipt_payment_item_label)

        // Unit price text and line total
        val unitPriceText = view.findViewById<TextView>(R.id.item_unit_price)
        val totalText = view.findViewById<TextView>(R.id.item_total)
        val vatDetailRow = view.findViewById<LinearLayout>(R.id.vat_detail_row)
        
        if (enteredAmount > 0) {
            // Fiat payment
            val amount = Amount(enteredAmount, currency)
            unitPriceText.text = amount.toString()
            totalText.text = amount.toString()
            
            // Show sats equivalent
            if (totalSatoshis > 0) {
                val satsAmount = Amount(totalSatoshis, Amount.Currency.BTC)
                val vatLabel = view.findViewById<TextView>(R.id.vat_label)
                val vatAmountText = view.findViewById<TextView>(R.id.vat_amount)
                vatLabel.text = getString(R.string.basket_receipt_paid_equivalent_label)
                vatAmountText.text = satsAmount.toString()
                vatDetailRow.visibility = View.VISIBLE
            } else {
                vatDetailRow.visibility = View.GONE
            }
        } else {
            // Sats payment
            val satsAmount = Amount(totalSatoshis, Amount.Currency.BTC)
            unitPriceText.text = satsAmount.toString()
            totalText.text = satsAmount.toString()
            vatDetailRow.visibility = View.GONE
        }
    }

    private fun displayTotals() {
        val b = basket
        val currency = b?.let { Amount.Currency.fromCode(it.currency) } 
            ?: Amount.Currency.fromCode(enteredCurrency)
        
        val showSatsAsPrimary = shouldShowSatsAsPrimary()
        
        // Use BASE amounts (excluding tip) for totals - tip is shown separately below
        val fullSats = b?.totalSatoshis ?: totalSatoshis
        val baseSats = fullSats - tipAmountSats
        val baseFiat = getTotalFiatIncludingSatsConversion() // enteredAmount is already base amount

        if (b != null) {
            val hasVat = b.hasVat()
            val hasFiatItems = b.getFiatItems().isNotEmpty()
            val hasSatsItems = b.getSatsItems().isNotEmpty()

            // Fiat subtotal row (net, only if there's VAT to show)
            if (hasVat && hasFiatItems) {
                val netTotal = b.getFiatNetTotalCents()
                subtotalLabel.text = "Fiat Subtotal (net)"
                subtotalValue.text = Amount(netTotal, currency).toString()
                subtotalRow.visibility = View.VISIBLE
            } else if (hasFiatItems && hasSatsItems) {
                // Show fiat subtotal if mixed basket
                subtotalLabel.text = "Fiat Items"
                subtotalValue.text = Amount(b.getFiatGrossTotalCents(), currency).toString()
                subtotalRow.visibility = View.VISIBLE
            } else {
                subtotalRow.visibility = View.GONE
            }

            // VAT breakdown by rate
            vatBreakdownContainer.removeAllViews()
            if (hasVat && hasFiatItems) {
                val vatBreakdown = b.getVatBreakdown()
                vatBreakdown.forEach { (rate, amount) ->
                    addVatRow(rate, amount, currency)
                }
            }
            
            // Sats items subtotal if mixed basket
            if (hasSatsItems) {
                satsItemsValue.text = Amount(b.getSatsDirectTotal(), Amount.Currency.BTC).toString()
                
                if (bitcoinPrice != null && bitcoinPrice!! > 0) {
                    val satsInFiat = ((b.getSatsDirectTotal().toDouble() / 100_000_000.0) * bitcoinPrice!! * 100).toLong()
                    satsItemsEquiv.text = "≈ ${Amount(satsInFiat, currency)}"
                    satsItemsEquiv.visibility = View.VISIBLE
                } else {
                    satsItemsEquiv.visibility = View.GONE
                }
                satsItemsRow.visibility = View.VISIBLE
            } else {
                satsItemsRow.visibility = View.GONE
            }
        } else {
            // No basket
            subtotalRow.visibility = View.GONE
            vatBreakdownContainer.removeAllViews()
            satsItemsRow.visibility = View.GONE
        }

        // Final total (BASE amount, excluding tip) - this is for accounting
        if (showSatsAsPrimary) {
            finalTotalLabel.text = "Total"
            finalTotalValue.text = Amount(baseSats, Amount.Currency.BTC).toString()
            
            if (baseFiat > 0) {
                satsEquivalentText.text = "≈ ${Amount(baseFiat, currency)}"
                satsEquivalentText.visibility = View.VISIBLE
            } else {
                satsEquivalentText.visibility = View.GONE
            }
        } else {
            finalTotalLabel.text = "Total"
            finalTotalValue.text = Amount(baseFiat, currency).toString()
            
            if (baseSats > 0) {
                satsEquivalentText.text = Amount(baseSats, Amount.Currency.BTC).toString()
                satsEquivalentText.visibility = View.VISIBLE
            } else {
                satsEquivalentText.visibility = View.GONE
            }
        }

        // Show tip as separate line AFTER total - it doesn't add to the Total for accounting
        if (tipAmountSats > 0) {
            addTipRow(currency)
            
            // Also add a "Total Paid" line showing the full amount with tip
            addTotalPaidRow(currency, fullSats)
        }
    }
    
    private fun addTotalPaidRow(currency: Amount.Currency, totalSats: Long) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (12 * resources.displayMetrics.density).toInt()
            }
        }

        val label = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = getString(R.string.basket_receipt_total_paid_label)
            textSize = 15f
            setTextColor(resources.getColor(R.color.color_text_primary, theme))
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }

        val value = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            text = Amount(totalSats, Amount.Currency.BTC).toString()
            textSize = 15f
            setTextColor(resources.getColor(R.color.color_text_primary, theme))
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }

        row.addView(label)
        row.addView(value)
        vatBreakdownContainer.addView(row)
    }

    private fun addTipRow(currency: Amount.Currency) {
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
            text = if (tipPercentage > 0) getString(R.string.basket_receipt_tip_label_with_percentage, tipPercentage) else getString(R.string.basket_receipt_tip_label)
            textSize = 15f
            setTextColor(resources.getColor(R.color.color_success_green, theme))
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }

        val value = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            text = Amount(tipAmountSats, Amount.Currency.BTC).toString()
            textSize = 15f
            setTextColor(resources.getColor(R.color.color_success_green, theme))
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }

        row.addView(label)
        row.addView(value)
        vatBreakdownContainer.addView(row)
    }

    private fun addVatRow(rate: Int, amountCents: Long, currency: Amount.Currency) {
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

    private fun displayPaymentInfo() {
        val sats = basket?.totalSatoshis ?: totalSatoshis
        paidAmountText.text = Amount(sats, Amount.Currency.BTC).toString()
    }

    private fun addDivider(container: LinearLayout) {
        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (0.5f * resources.displayMetrics.density).toInt()
            ).apply {
                marginStart = (56 * resources.displayMetrics.density).toInt() // Align with item text
                marginEnd = (16 * resources.displayMetrics.density).toInt()
            }
            setBackgroundColor(resources.getColor(R.color.color_divider, theme))
        }
        container.addView(divider)
    }

    companion object {
        const val EXTRA_CHECKOUT_BASKET_JSON = "checkout_basket_json"
        const val EXTRA_PAYMENT_TYPE = "payment_type"
        const val EXTRA_PAYMENT_DATE = "payment_date"
        const val EXTRA_TRANSACTION_ID = "transaction_id"
        const val EXTRA_MINT_URL = "mint_url"
        const val EXTRA_BITCOIN_PRICE = "bitcoin_price"
        const val EXTRA_TOTAL_SATOSHIS = "total_satoshis"
        const val EXTRA_ENTERED_AMOUNT = "entered_amount"
        const val EXTRA_ENTERED_CURRENCY = "entered_currency"
        const val EXTRA_TIP_AMOUNT_SATS = "tip_amount_sats"
        const val EXTRA_TIP_PERCENTAGE = "tip_percentage"
    }
}
