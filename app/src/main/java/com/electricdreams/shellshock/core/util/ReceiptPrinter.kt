package com.electricdreams.shellshock.core.util

import android.content.Context
import android.content.Intent
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.FileProvider
import com.electricdreams.shellshock.core.model.Amount
import com.electricdreams.shellshock.core.model.CheckoutBasket
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Utility class for generating and printing thermal receipt-style receipts.
 * Produces a narrow-format, fixed-width font receipt suitable for thermal printers.
 * 
 * Handles three pricing scenarios:
 * 1. Fiat-only basket: Shows fiat total as primary, sats as secondary
 * 2. Mixed basket (fiat + sats items): Shows sats total as primary, fiat equivalent as secondary
 * 3. Sats-only basket: Shows sats total as primary, fiat equivalent as secondary
 * 4. No basket (direct payment): Shows single "Payment" line item
 */
class ReceiptPrinter(private val context: Context) {

    companion object {
        // Standard thermal receipt width (58mm or 80mm paper)
        private const val RECEIPT_WIDTH_CHARS = 42 // Characters per line for 58mm paper
        
        private const val LINE_SEPARATOR = "──────────────────────────────────────────"
        private const val DOUBLE_LINE = "══════════════════════════════════════════"
    }

    /**
     * Data class containing all information needed to print a receipt.
     * Basket is optional - if null, creates a simple "Payment" receipt.
     */
    data class ReceiptData(
        val basket: CheckoutBasket?, // null for transactions without basket
        val paymentType: String?, // "cashu" or "lightning"
        val paymentDate: Date,
        val transactionId: String?,
        val mintUrl: String?,
        val bitcoinPrice: Double?,
        val merchantName: String = "SHELLSHOCK POS",
        val merchantAddress: String? = null,
        val merchantVatNumber: String? = null,
        // For non-basket transactions
        val totalSatoshis: Long = 0,
        val enteredAmount: Long = 0, // in minor units (cents)
        val enteredCurrency: String = "USD",
        // Tip information
        val tipAmountSats: Long = 0,
        val tipPercentage: Int = 0,
    )

    /**
     * Determine if the receipt should show sats as the primary amount.
     * True for: mixed baskets, sats-only baskets, or when no fiat items exist.
     */
    private fun shouldShowSatsAsPrimary(data: ReceiptData): Boolean {
        val basket = data.basket ?: return false // No basket = use fiat if available
        
        // Mixed pricing or sats-only = show sats as primary
        return basket.hasMixedPriceTypes() || basket.getFiatItems().isEmpty()
    }

    /**
     * Calculate total fiat value including converted sats items.
     */
    private fun getTotalFiatIncludingSatsConversion(data: ReceiptData): Long {
        val basket = data.basket ?: return data.enteredAmount
        
        val fiatTotal = basket.getFiatGrossTotalCents()
        val satsItems = basket.getSatsItems()
        
        if (satsItems.isEmpty() || data.bitcoinPrice == null || data.bitcoinPrice <= 0) {
            return fiatTotal
        }
        
        // Convert sats items to fiat
        val satsTotal = basket.getSatsDirectTotal()
        val satsInFiat = ((satsTotal.toDouble() / 100_000_000.0) * data.bitcoinPrice * 100).toLong()
        
        return fiatTotal + satsInFiat
    }

    /**
     * Generate a plain-text receipt suitable for thermal printers.
     */
    fun generateTextReceipt(data: ReceiptData): String {
        val sb = StringBuilder()
        val w = RECEIPT_WIDTH_CHARS
        
        // Helper functions
        fun center(text: String): String {
            val padding = (w - text.length) / 2
            return " ".repeat(maxOf(0, padding)) + text
        }
        
        fun leftRight(left: String, right: String): String {
            val spaces = w - left.length - right.length
            return left + " ".repeat(maxOf(1, spaces)) + right
        }
        
        fun line() = LINE_SEPARATOR.take(w)
        fun doubleLine() = DOUBLE_LINE.take(w)

        // Currency helper
        val currency = data.basket?.let { Amount.Currency.fromCode(it.currency) } 
            ?: Amount.Currency.fromCode(data.enteredCurrency)
        fun formatFiat(cents: Long): String = Amount(cents, currency).toString()
        fun formatSats(sats: Long): String = Amount(sats, Amount.Currency.BTC).toString()

        val showSatsAsPrimary = shouldShowSatsAsPrimary(data)
        val totalSats = data.basket?.totalSatoshis ?: data.totalSatoshis

        // ═══════════════════════════════════════════
        // HEADER
        // ═══════════════════════════════════════════
        sb.appendLine()
        sb.appendLine(center(data.merchantName))
        data.merchantAddress?.let { sb.appendLine(center(it)) }
        data.merchantVatNumber?.let { sb.appendLine(center("VAT: $it")) }
        sb.appendLine()
        sb.appendLine(doubleLine())
        sb.appendLine(center("RECEIPT"))
        sb.appendLine(doubleLine())
        sb.appendLine()

        // ───────────────────────────────────────────
        // DATE & TRANSACTION INFO
        // ───────────────────────────────────────────
        val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
        sb.appendLine(leftRight("Date:", dateFormat.format(data.paymentDate)))
        
        data.transactionId?.let { txId ->
            val shortTxId = if (txId.length > 16) txId.take(8) + "..." + txId.takeLast(4) else txId
            sb.appendLine(leftRight("Tx ID:", shortTxId))
        }
        
        sb.appendLine()
        sb.appendLine(line())

        // ───────────────────────────────────────────
        // ITEMS
        // ───────────────────────────────────────────
        sb.appendLine(center("ITEMS"))
        sb.appendLine(line())
        sb.appendLine()

        val basket = data.basket
        if (basket != null && basket.items.isNotEmpty()) {
            // Display each item with its original price
            basket.items.forEach { item ->
                val itemName = item.displayName
                val truncatedName = if (itemName.length > w - 12) {
                    itemName.take(w - 15) + "..."
                } else {
                    itemName
                }
                
                // Show price in original currency (fiat or sats)
                if (item.isFiatPrice()) {
                    val unitPrice = formatFiat(item.getGrossPricePerUnitCents())
                    val lineTotal = formatFiat(item.getGrossTotalCents())
                    
                    sb.appendLine(truncatedName)
                    sb.appendLine(leftRight("  ${item.quantity} x $unitPrice", lineTotal))
                    
                    // VAT detail
                    if (item.vatEnabled && item.vatRate > 0) {
                        val vatAmount = formatFiat(item.getTotalVatCents())
                        sb.appendLine("  (incl. ${item.vatRate}% VAT: $vatAmount)")
                    }
                } else {
                    // Sats-priced item
                    val unitPrice = formatSats(item.priceSats)
                    val lineTotal = formatSats(item.getNetTotalSats())
                    
                    sb.appendLine(truncatedName)
                    sb.appendLine(leftRight("  ${item.quantity} x $unitPrice", lineTotal))
                    
                    // Show fiat equivalent if we have bitcoin price
                    if (data.bitcoinPrice != null && data.bitcoinPrice > 0) {
                        val satsInFiat = ((item.getNetTotalSats().toDouble() / 100_000_000.0) * data.bitcoinPrice * 100).toLong()
                        sb.appendLine("  (≈ ${formatFiat(satsInFiat)})")
                    }
                }
                
                sb.appendLine()
            }
        } else {
            // No basket - single "Payment" line
            sb.appendLine("Payment")
            if (data.enteredAmount > 0) {
                sb.appendLine(leftRight("  1 x ${formatFiat(data.enteredAmount)}", formatFiat(data.enteredAmount)))
            } else {
                sb.appendLine(leftRight("  1 x ${formatSats(totalSats)}", formatSats(totalSats)))
            }
            sb.appendLine()
        }

        sb.appendLine(line())

        // ───────────────────────────────────────────
        // TOTALS
        // ───────────────────────────────────────────
        if (basket != null) {
            val hasVat = basket.hasVat()
            val hasFiatItems = basket.getFiatItems().isNotEmpty()

            if (hasVat && hasFiatItems) {
                // Net subtotal (fiat items only)
                val netTotal = formatFiat(basket.getFiatNetTotalCents())
                sb.appendLine(leftRight("Fiat Subtotal (net):", netTotal))

                // VAT breakdown
                basket.getVatBreakdown().forEach { (rate, amountCents) ->
                    val vatAmount = formatFiat(amountCents)
                    sb.appendLine(leftRight("VAT ($rate%):", vatAmount))
                }
                
                // Fiat gross subtotal
                val grossFiat = formatFiat(basket.getFiatGrossTotalCents())
                sb.appendLine(leftRight("Fiat Subtotal (gross):", grossFiat))
                
                sb.appendLine(line())
            }

            // Sats items subtotal if present
            if (basket.getSatsItems().isNotEmpty()) {
                val satsSubtotal = formatSats(basket.getSatsDirectTotal())
                sb.appendLine(leftRight("Bitcoin Items:", satsSubtotal))
                
                // Show fiat equivalent
                if (data.bitcoinPrice != null && data.bitcoinPrice > 0) {
                    val satsInFiat = ((basket.getSatsDirectTotal().toDouble() / 100_000_000.0) * data.bitcoinPrice * 100).toLong()
                    sb.appendLine(leftRight("  (equivalent):", "≈ ${formatFiat(satsInFiat)}"))
                }
                sb.appendLine(line())
            }
        }

        // GRAND TOTAL - show BASE amount (excluding tip) for accounting
        val baseSats = totalSats - data.tipAmountSats
        val baseFiat = getTotalFiatIncludingSatsConversion(data) // enteredAmount is already base
        
        sb.appendLine()
        if (showSatsAsPrimary || (basket == null && data.enteredAmount == 0L)) {
            // Primary: Sats (base amount)
            sb.appendLine(leftRight("TOTAL:", formatSats(baseSats)))
            
            // Secondary: Fiat equivalent
            if (baseFiat > 0) {
                sb.appendLine(leftRight("  (equivalent):", "≈ ${formatFiat(baseFiat)}"))
            }
        } else {
            // Primary: Fiat (base amount)
            sb.appendLine(leftRight("TOTAL:", formatFiat(baseFiat)))
            
            // Secondary: Sats
            sb.appendLine(leftRight("  (paid):", formatSats(baseSats)))
        }
        sb.appendLine()

        // Add tip as separate line AFTER total (not included in total for accounting)
        if (data.tipAmountSats > 0) {
            sb.appendLine(line())
            val tipLabel = if (data.tipPercentage > 0) {
                "Tip (${data.tipPercentage}%):"
            } else {
                "Tip:"
            }
            sb.appendLine(leftRight(tipLabel, formatSats(data.tipAmountSats)))
            sb.appendLine(leftRight("TOTAL PAID:", formatSats(totalSats)))
            sb.appendLine()
        }

        // Bitcoin price at time of transaction
        data.bitcoinPrice?.let { price ->
            val formattedPrice = String.format(Locale.US, "$%,.2f", price)
            sb.appendLine(leftRight("BTC/USD Rate:", formattedPrice))
        }

        sb.appendLine()
        sb.appendLine(doubleLine())

        // ───────────────────────────────────────────
        // PAYMENT INFO
        // ───────────────────────────────────────────
        sb.appendLine()
        
        val paymentMethod = when (data.paymentType) {
            "lightning" -> "⚡ Lightning Network"
            "cashu" -> "🥜 Cashu (ecash)"
            else -> "Bitcoin"
        }
        sb.appendLine(leftRight("Payment:", paymentMethod))
        
        val paidAmount = formatSats(totalSats)
        sb.appendLine(leftRight("Paid:", paidAmount))
        sb.appendLine(leftRight("Status:", "✓ PAID"))

        data.mintUrl?.let { url ->
            sb.appendLine()
            val shortUrl = if (url.length > w - 6) {
                url.take(w - 9) + "..."
            } else {
                url
            }
            sb.appendLine(leftRight("Mint:", shortUrl))
        }

        sb.appendLine()
        sb.appendLine(doubleLine())

        // ───────────────────────────────────────────
        // FOOTER
        // ───────────────────────────────────────────
        sb.appendLine()
        sb.appendLine(center("Thank you for your purchase!"))
        sb.appendLine()
        sb.appendLine(center("Powered by Bitcoin"))
        sb.appendLine(center("⚡"))
        sb.appendLine()

        return sb.toString()
    }

    /**
     * Generate an HTML receipt for web view or PDF printing.
     */
    fun generateHtmlReceipt(data: ReceiptData): String {
        val currency = data.basket?.let { Amount.Currency.fromCode(it.currency) } 
            ?: Amount.Currency.fromCode(data.enteredCurrency)
        fun formatFiat(cents: Long): String = Amount(cents, currency).toString()
        fun formatSats(sats: Long): String = Amount(sats, Amount.Currency.BTC).toString()
        
        val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
        val basket = data.basket
        val hasVat = basket?.hasVat() ?: false
        val hasFiatItems = basket?.getFiatItems()?.isNotEmpty() ?: false
        val showSatsAsPrimary = shouldShowSatsAsPrimary(data)
        val totalSats = basket?.totalSatoshis ?: data.totalSatoshis
        val totalFiat = getTotalFiatIncludingSatsConversion(data)
        
        // Build items HTML
        val itemsHtml = if (basket != null && basket.items.isNotEmpty()) {
            basket.items.joinToString("") { item ->
                if (item.isFiatPrice()) {
                    val lineTotal = formatFiat(item.getGrossTotalCents())
                    val unitPrice = formatFiat(item.getGrossPricePerUnitCents())
                    val vatInfo = if (item.vatEnabled && item.vatRate > 0) {
                        "<div class=\"vat-detail\">(incl. ${item.vatRate}% VAT: ${formatFiat(item.getTotalVatCents())})</div>"
                    } else ""
                    """
                    <div class="item">
                        <div class="row">
                            <span class="item-name">${item.displayName}</span>
                            <span class="bold">$lineTotal</span>
                        </div>
                        <div class="item-detail">${item.quantity} × $unitPrice</div>
                        $vatInfo
                    </div>
                    """
                } else {
                    // Sats item
                    val lineTotal = formatSats(item.getNetTotalSats())
                    val unitPrice = formatSats(item.priceSats)
                    val fiatEquiv = if (data.bitcoinPrice != null && data.bitcoinPrice > 0) {
                        val satsInFiat = ((item.getNetTotalSats().toDouble() / 100_000_000.0) * data.bitcoinPrice * 100).toLong()
                        "<div class=\"item-detail small\">(≈ ${formatFiat(satsInFiat)})</div>"
                    } else ""
                    """
                    <div class="item">
                        <div class="row">
                            <span class="item-name">${item.displayName}</span>
                            <span class="bold">$lineTotal</span>
                        </div>
                        <div class="item-detail">${item.quantity} × $unitPrice</div>
                        $fiatEquiv
                    </div>
                    """
                }
            }
        } else {
            // No basket - single Payment line
            val amount = if (data.enteredAmount > 0) formatFiat(data.enteredAmount) else formatSats(totalSats)
            """
            <div class="item">
                <div class="row">
                    <span class="item-name">Payment</span>
                    <span class="bold">$amount</span>
                </div>
                <div class="item-detail">1 × $amount</div>
            </div>
            """
        }

        // Build totals HTML
        val totalsHtml = buildString {
            if (basket != null && hasVat && hasFiatItems) {
                append("""
                <div class="row">
                    <span>Fiat Subtotal (net):</span>
                    <span>${formatFiat(basket.getFiatNetTotalCents())}</span>
                </div>
                """)
                basket.getVatBreakdown().entries.forEach { (rate, amount) ->
                    append("<div class=\"row\"><span>VAT ($rate%):</span><span>${formatFiat(amount)}</span></div>")
                }
                append("""
                <div class="row">
                    <span>Fiat Subtotal (gross):</span>
                    <span>${formatFiat(basket.getFiatGrossTotalCents())}</span>
                </div>
                <div class="divider"></div>
                """)
            }
            
            if (basket != null && basket.getSatsItems().isNotEmpty()) {
                append("""
                <div class="row">
                    <span>Bitcoin Items:</span>
                    <span>${formatSats(basket.getSatsDirectTotal())}</span>
                </div>
                """)
                if (data.bitcoinPrice != null && data.bitcoinPrice > 0) {
                    val satsInFiat = ((basket.getSatsDirectTotal().toDouble() / 100_000_000.0) * data.bitcoinPrice * 100).toLong()
                    append("""
                    <div class="row small">
                        <span>&nbsp;&nbsp;(equivalent):</span>
                        <span>≈ ${formatFiat(satsInFiat)}</span>
                    </div>
                    """)
                }
                append("<div class=\"divider\"></div>")
            }
            
            // Note: tip is shown AFTER total in HTML, not in totalsHtml
        }

        // Use BASE amounts (excluding tip) for the total - tip is shown separately
        val baseSats = totalSats - data.tipAmountSats
        val baseFiat = totalFiat // enteredAmount is already base

        // Primary/secondary amount display (BASE amounts for accounting)
        val primaryTotal: String
        val secondaryTotal: String
        if (showSatsAsPrimary || (basket == null && data.enteredAmount == 0L)) {
            primaryTotal = formatSats(baseSats)
            secondaryTotal = if (baseFiat > 0) "≈ ${formatFiat(baseFiat)}" else ""
        } else {
            primaryTotal = formatFiat(baseFiat)
            secondaryTotal = formatSats(baseSats)
        }
        
        // Tip section HTML (shown after total)
        val tipSectionHtml = if (data.tipAmountSats > 0) {
            val tipLabel = if (data.tipPercentage > 0) "Tip (${data.tipPercentage}%)" else "Tip"
            """
            <div class="divider"></div>
            <div class="row" style="color: #00C244; font-weight: bold;">
                <span>$tipLabel:</span>
                <span>${formatSats(data.tipAmountSats)}</span>
            </div>
            <div class="row total-row">
                <span>TOTAL PAID:</span>
                <span>${formatSats(totalSats)}</span>
            </div>
            """
        } else ""
        
        return """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=80mm">
    <style>
        @page {
            size: 80mm auto;
            margin: 0;
        }
        * {
            margin: 0;
            padding: 0;
            box-sizing: border-box;
        }
        body {
            font-family: 'Courier New', Courier, monospace;
            font-size: 12px;
            line-height: 1.4;
            width: 80mm;
            max-width: 80mm;
            padding: 8mm 4mm;
            background: white;
            color: black;
        }
        .center { text-align: center; }
        .right { text-align: right; }
        .bold { font-weight: bold; }
        .large { font-size: 16px; }
        .small { font-size: 10px; color: #666; }
        .header { margin-bottom: 8px; }
        .merchant-name { font-size: 18px; font-weight: bold; }
        .divider { 
            border-top: 1px dashed #000; 
            margin: 8px 0; 
        }
        .double-divider { 
            border-top: 2px solid #000; 
            margin: 8px 0; 
        }
        .row {
            display: flex;
            justify-content: space-between;
            margin: 2px 0;
        }
        .row-label { flex: 1; }
        .row-value { text-align: right; }
        .item { margin: 8px 0; }
        .item-name { font-weight: bold; }
        .item-detail { padding-left: 8px; color: #333; }
        .vat-detail { font-size: 10px; color: #666; padding-left: 8px; }
        .total-row { font-size: 14px; font-weight: bold; margin: 4px 0; }
        .secondary-total { font-size: 11px; color: #666; }
        .paid-badge {
            display: inline-block;
            background: #000;
            color: #fff;
            padding: 2px 8px;
            border-radius: 2px;
            font-size: 10px;
        }
        .footer { margin-top: 16px; }
        .bitcoin-symbol { font-size: 24px; }
    </style>
</head>
<body>
    <!-- Header -->
    <div class="header center">
        <div class="merchant-name">${data.merchantName}</div>
        ${data.merchantAddress?.let { "<div class=\"small\">$it</div>" } ?: ""}
        ${data.merchantVatNumber?.let { "<div class=\"small\">VAT: $it</div>" } ?: ""}
    </div>
    
    <div class="double-divider"></div>
    <div class="center bold large">RECEIPT</div>
    <div class="double-divider"></div>
    
    <!-- Transaction Info -->
    <div class="row">
        <span>Date:</span>
        <span>${dateFormat.format(data.paymentDate)}</span>
    </div>
    ${data.transactionId?.let { 
        val shortId = if (it.length > 16) it.take(8) + "..." + it.takeLast(4) else it
        "<div class=\"row\"><span>Tx ID:</span><span class=\"small\">$shortId</span></div>" 
    } ?: ""}
    
    <div class="divider"></div>
    <div class="center bold">ITEMS</div>
    <div class="divider"></div>
    
    <!-- Items -->
    $itemsHtml
    
    <div class="divider"></div>
    
    <!-- Totals -->
    $totalsHtml
    
    <div class="row total-row">
        <span>TOTAL:</span>
        <span>$primaryTotal</span>
    </div>
    ${if (secondaryTotal.isNotEmpty()) """
    <div class="row secondary-total">
        <span>&nbsp;&nbsp;${if (showSatsAsPrimary) "(equivalent):" else "(paid):"}</span>
        <span>$secondaryTotal</span>
    </div>
    """ else ""}
    
    $tipSectionHtml
    
    ${data.bitcoinPrice?.let { """
    <div class="row small" style="margin-top: 4px;">
        <span>BTC/USD Rate:</span>
        <span>${String.format(Locale.US, "$%,.2f", it)}</span>
    </div>
    """ } ?: ""}
    
    <div class="double-divider"></div>
    
    <!-- Payment Info -->
    <div class="row">
        <span>Payment:</span>
        <span>${when (data.paymentType) {
            "lightning" -> "⚡ Lightning"
            "cashu" -> "🥜 Cashu"
            else -> "Bitcoin"
        }}</span>
    </div>
    <div class="row">
        <span>Paid:</span>
        <span>${formatSats(totalSats)}</span>
    </div>
    <div class="row">
        <span>Status:</span>
        <span class="paid-badge">✓ PAID</span>
    </div>
    
    ${data.mintUrl?.let { """
    <div class="row small" style="margin-top: 4px;">
        <span>Mint:</span>
        <span style="word-break: break-all; max-width: 50mm;">${if (it.length > 30) it.take(27) + "..." else it}</span>
    </div>
    """ } ?: ""}
    
    <div class="double-divider"></div>
    
    <!-- Footer -->
    <div class="footer center">
        <div>Thank you for your purchase!</div>
        <div class="small" style="margin-top: 8px;">Powered by Bitcoin</div>
        <div class="bitcoin-symbol">⚡</div>
    </div>
</body>
</html>
        """.trimIndent()
    }

    /**
     * Print the receipt using Android's print framework.
     */
    fun printReceipt(data: ReceiptData) {
        val printManager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
        val html = generateHtmlReceipt(data)
        
        val webView = WebView(context)
        webView.settings.apply {
            javaScriptEnabled = false
            allowFileAccess = false
        }
        
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                val jobName = "${data.merchantName} Receipt"
                val printAdapter = webView.createPrintDocumentAdapter(jobName)
                
                val attributes = PrintAttributes.Builder()
                    .setMediaSize(PrintAttributes.MediaSize.ISO_A7) // Small receipt-like size
                    .setColorMode(PrintAttributes.COLOR_MODE_MONOCHROME)
                    .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                    .build()
                
                printManager.print(jobName, printAdapter, attributes)
            }
        }
        
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    /**
     * Share the receipt as text (for messaging apps, email, etc.)
     */
    fun shareReceipt(data: ReceiptData) {
        val receiptText = generateTextReceipt(data)
        
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "${data.merchantName} Receipt")
            putExtra(Intent.EXTRA_TEXT, receiptText)
        }
        
        val chooser = Intent.createChooser(shareIntent, "Share Receipt")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    /**
     * Save receipt as text file and share.
     */
    fun shareReceiptAsFile(data: ReceiptData) {
        val receiptText = generateTextReceipt(data)
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val fileName = "receipt_${dateFormat.format(data.paymentDate)}.txt"
        
        try {
            val cacheDir = File(context.cacheDir, "receipts")
            cacheDir.mkdirs()
            val file = File(cacheDir, fileName)
            
            FileOutputStream(file).use { fos ->
                fos.write(receiptText.toByteArray(Charsets.UTF_8))
            }
            
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "${data.merchantName} Receipt")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            val chooser = Intent.createChooser(shareIntent, "Share Receipt")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (e: Exception) {
            // Fallback to plain text share
            shareReceipt(data)
        }
    }
}
