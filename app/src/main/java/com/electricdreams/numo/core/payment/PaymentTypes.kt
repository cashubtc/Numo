package com.electricdreams.numo.core.payment

import com.electricdreams.numo.core.wallet.Satoshis

/**
 * Data returned after creating a payment.
 */
data class PaymentData(
    /** Quote ID (local) or invoice ID (BTCPay) */
    val paymentId: String,
    /** BOLT11 Lightning invoice (if available) */
    val bolt11: String?,
    /** Cashu payment request (`creq…`) from BTCNutServer – null for local mode where Numo builds its own */
    val cashuPR: String?,
    /** Mint URL used for the quote (local mode) – null for BTCPay */
    val mintUrl: String?,
    /** Unix-epoch expiry timestamp (optional) */
    val expiresAt: Long? = null
)

/**
 * Possible states of a payment.
 */
enum class PaymentState {
    PENDING,
    PAID,
    EXPIRED,
    FAILED
}

/**
 * Result of redeeming a Cashu token for a payment.
 */
data class RedeemResult(
    /** Amount received in satoshis */
    val amount: Satoshis,
    /** Number of proofs received */
    val proofsCount: Int
)
