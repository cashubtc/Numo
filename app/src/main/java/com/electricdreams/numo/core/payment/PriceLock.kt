package com.electricdreams.numo.core.payment

/**
 * How long a quoted price holds. Within this window a sale keeps the price it was quoted at,
 * even though the bitcoin price moves every minute; after it, the sale is priced again.
 * Used for BTCPay invoices and for card charges.
 */
object PriceLock {
    const val DURATION_MS = 15 * 60 * 1000L
}
