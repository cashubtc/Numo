package com.electricdreams.numo.core.payment

import com.electricdreams.numo.core.wallet.WalletResult

/**
 * Abstraction for POS payment operations.
 *
 * Implementations:
 * - [com.electricdreams.numo.core.payment.impl.LocalPaymentService] – wraps existing CDK flow
 * - [com.electricdreams.numo.core.payment.impl.BtcPayPaymentService] – BTCPay Greenfield API + BTCNutServer
 */
interface PaymentService {

    /**
     * Create a new payment (invoice / mint quote).
     *
     * @param amountSats Amount in satoshis
     * @param description Optional human-readable description
     * @return [PaymentData] with invoice details
     */
    suspend fun createPayment(
        amountSats: Long,
        description: String? = null
    ): WalletResult<PaymentData>

    /**
     * Poll the current status of a payment.
     *
     * @param paymentId The id returned in [PaymentData.paymentId]
     */
    suspend fun checkPaymentStatus(paymentId: String): WalletResult<PaymentState>

    /**
     * Redeem a Cashu token received for a payment.
     *
     * @param token Encoded Cashu token string
     * @param paymentId Optional payment/invoice id (used by BTCPay to link token to invoice)
     */
    suspend fun redeemToken(
        token: String,
        paymentId: String? = null
    ): WalletResult<RedeemResult>

    /**
     * Whether the service is ready to create payments.
     */
    fun isReady(): Boolean
}
