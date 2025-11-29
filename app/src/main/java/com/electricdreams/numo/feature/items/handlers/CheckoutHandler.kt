package com.electricdreams.numo.feature.items.handlers

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import com.electricdreams.numo.PaymentRequestActivity
import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.core.model.CheckoutBasket
import com.electricdreams.numo.core.util.BasketManager
import com.electricdreams.numo.core.util.CurrencyManager
import com.electricdreams.numo.core.worker.BitcoinPriceWorker

/**
 * Handles checkout logic and navigation to payment screen.
 */
class CheckoutHandler(
    private val activity: Activity,
    private val basketManager: BasketManager,
    private val currencyManager: CurrencyManager,
    private val bitcoinPriceWorker: BitcoinPriceWorker
) {
    
    // Optional saved basket ID to associate with payment
    var savedBasketId: String? = null

    /**
     * Proceed to checkout if basket is valid.
     * Calculates totals, formats the amount, and navigates to PaymentRequestActivity.
     * Captures a snapshot of the basket for receipt documentation.
     */
    fun proceedToCheckout() {
        if (basketManager.getTotalItemCount() == 0) {
            Toast.makeText(activity, "Your basket is empty", Toast.LENGTH_SHORT).show()
            return
        }

        val fiatTotal = basketManager.getTotalPrice()
        val satsTotal = basketManager.getTotalSatsDirectPrice()
        val btcPrice = bitcoinPriceWorker.getCurrentPrice()

        // Calculate total in satoshis
        val totalSatoshis = basketManager.getTotalSatoshis(btcPrice)

        if (totalSatoshis <= 0) {
            Toast.makeText(activity, "Invalid payment amount", Toast.LENGTH_SHORT).show()
            return
        }

        // Determine how to format the amount for PaymentRequestActivity
        val formattedAmount = formatPaymentAmount(fiatTotal, satsTotal)

        // Create a snapshot of the basket BEFORE clearing it
        // This preserves the checkout data for receipt generation
        val checkoutBasket = CheckoutBasket.fromBasketManager(
            basketManager = basketManager,
            currency = currencyManager.getCurrentCurrency(),
            bitcoinPrice = if (btcPrice > 0) btcPrice else null,
            totalSatoshis = totalSatoshis,
        )
        val checkoutBasketJson = checkoutBasket.toJson()
        
        android.util.Log.d("CheckoutHandler", "Captured basket with ${checkoutBasket.items.size} items, total: $totalSatoshis sats")
        android.util.Log.d("CheckoutHandler", "Basket JSON size: ${checkoutBasketJson.length} chars")

        val intent = Intent(activity, PaymentRequestActivity::class.java).apply {
            putExtra(PaymentRequestActivity.EXTRA_PAYMENT_AMOUNT, totalSatoshis)
            putExtra(PaymentRequestActivity.EXTRA_FORMATTED_AMOUNT, formattedAmount)
            putExtra(PaymentRequestActivity.EXTRA_CHECKOUT_BASKET_JSON, checkoutBasketJson)
            savedBasketId?.let { putExtra(PaymentRequestActivity.EXTRA_SAVED_BASKET_ID, it) }
        }

        basketManager.clearBasket()
        activity.startActivity(intent)
        activity.finish()
    }

    /**
     * Format the payment amount based on pricing type.
     * - Pure fiat: Display as fiat currency
     * - Pure sats: Display as BTC/sats
     * - Mixed: Treat as pure sats (display as BTC)
     */
    private fun formatPaymentAmount(fiatTotal: Double, satsTotal: Long): String {
        return when {
            // Pure fiat (no sats items) - display as fiat
            satsTotal == 0L && fiatTotal > 0 -> {
                val currencyCode = currencyManager.getCurrentCurrency()
                val currency = Amount.Currency.fromCode(currencyCode)
                val fiatCents = (fiatTotal * 100).toLong()
                Amount(fiatCents, currency).toString()
            }
            // Pure sats (no fiat items) - display as BTC/sats
            fiatTotal == 0.0 && satsTotal > 0 -> {
                Amount(satsTotal, Amount.Currency.BTC).toString()
            }
            // Mixed fiat + sats - treat as pure sats (display as BTC)
            else -> {
                val btcPrice = bitcoinPriceWorker.getCurrentPrice()
                val totalSatoshis = basketManager.getTotalSatoshis(btcPrice)
                Amount(totalSatoshis, Amount.Currency.BTC).toString()
            }
        }
    }
}
