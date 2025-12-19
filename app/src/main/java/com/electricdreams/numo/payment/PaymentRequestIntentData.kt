package com.electricdreams.numo.payment

import android.content.Intent
import android.util.Log
import com.electricdreams.numo.PaymentRequestActivity
import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.core.model.Amount.Currency
import com.electricdreams.numo.feature.tips.TipSelectionActivity

/**
 * Immutable snapshot of all runtime data required for PaymentRequestActivity.
 */
data class PaymentRequestIntentData(
    val paymentAmount: Long,
    val formattedAmount: String,
    val pendingPaymentId: String?,
    val resumeLightningQuoteId: String?,
    val resumeLightningMintUrl: String?,
    val resumeLightningInvoice: String?,
    val resumeNostrSecretHex: String?,
    val resumeNostrNprofile: String?,
    val checkoutBasketJson: String?,
    val savedBasketId: String?,
    val tipAmountSats: Long,
    val tipPercentage: Int,
    val baseAmountSats: Long,
    val baseFormattedAmount: String?,
) {
    val isResumingPayment: Boolean get() = pendingPaymentId != null
    val hasTip: Boolean get() = tipAmountSats > 0 && baseAmountSats > 0

    companion object {
        private const val TAG = "PaymentRequestIntentData"

        fun fromIntent(intent: Intent): PaymentRequestIntentData {
            val paymentAmount = intent.getLongExtra(PaymentRequestActivity.EXTRA_PAYMENT_AMOUNT, 0)
            val formattedAmount = intent.getStringExtra(PaymentRequestActivity.EXTRA_FORMATTED_AMOUNT)
                ?: Amount(paymentAmount, Currency.BTC).toString()

            val pendingPaymentId = intent.getStringExtra(PaymentRequestActivity.EXTRA_RESUME_PAYMENT_ID)

            val tipAmount = intent.getLongExtra(TipSelectionActivity.EXTRA_TIP_AMOUNT_SATS, 0)
            val tipPercentage = intent.getIntExtra(TipSelectionActivity.EXTRA_TIP_PERCENTAGE, 0)
            val baseAmount = intent.getLongExtra(TipSelectionActivity.EXTRA_BASE_AMOUNT_SATS, 0)
            val baseFormatted = intent.getStringExtra(TipSelectionActivity.EXTRA_BASE_FORMATTED_AMOUNT)

            if (tipAmount > 0) {
                Log.d(
                    TAG,
                    "Read tip info from intent: tipAmount=$tipAmount, tipPercent=$tipPercentage%, baseAmount=$baseAmount"
                )
            }

            return PaymentRequestIntentData(
                paymentAmount = paymentAmount,
                formattedAmount = formattedAmount,
                pendingPaymentId = pendingPaymentId,
                resumeLightningQuoteId = intent.getStringExtra(PaymentRequestActivity.EXTRA_LIGHTNING_QUOTE_ID),
                resumeLightningMintUrl = intent.getStringExtra(PaymentRequestActivity.EXTRA_LIGHTNING_MINT_URL),
                resumeLightningInvoice = intent.getStringExtra(PaymentRequestActivity.EXTRA_LIGHTNING_INVOICE),
                resumeNostrSecretHex = intent.getStringExtra(PaymentRequestActivity.EXTRA_NOSTR_SECRET_HEX),
                resumeNostrNprofile = intent.getStringExtra(PaymentRequestActivity.EXTRA_NOSTR_NPROFILE),
                checkoutBasketJson = intent.getStringExtra(PaymentRequestActivity.EXTRA_CHECKOUT_BASKET_JSON),
                savedBasketId = intent.getStringExtra(PaymentRequestActivity.EXTRA_SAVED_BASKET_ID),
                tipAmountSats = tipAmount,
                tipPercentage = tipPercentage,
                baseAmountSats = baseAmount,
                baseFormattedAmount = baseFormatted,
            )
        }
    }
}
