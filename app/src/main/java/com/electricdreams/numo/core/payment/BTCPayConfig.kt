package com.electricdreams.numo.core.payment

/**
 * Configuration for connecting to a BTCPay Server instance.
 */
data class BTCPayConfig(
    /** Base URL of the BTCPay Server (e.g. "https://btcpay.example.com") */
    val serverUrl: String,
    /** Greenfield API key */
    val apiKey: String,
    /** Store ID within BTCPay */
    val storeId: String,
    /** BTCPay POS app ID — when set, invoices are created via the POS endpoint */
    val posAppId: String? = null,
)
