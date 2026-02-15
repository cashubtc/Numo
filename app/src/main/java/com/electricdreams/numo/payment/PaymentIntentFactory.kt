package com.electricdreams.numo.payment

import android.content.Context
import android.content.Intent
import com.electricdreams.numo.PaymentRequestActivity
import com.electricdreams.numo.core.data.model.PaymentHistoryEntry
import com.electricdreams.numo.feature.history.TransactionDetailActivity

/**
 * Shared intent builders for payment resume and transaction details routes.
 *
 * Centralizing this avoids drift when extras evolve across callers.
 */
object PaymentIntentFactory {

    fun createResumePaymentIntent(
        context: Context,
        entry: PaymentHistoryEntry,
    ): Intent {
        return Intent(context, PaymentRequestActivity::class.java).apply {
            putExtra(PaymentRequestActivity.EXTRA_PAYMENT_AMOUNT, entry.amount)
            putExtra(PaymentRequestActivity.EXTRA_FORMATTED_AMOUNT, entry.formattedAmount)
            putExtra(PaymentRequestActivity.EXTRA_RESUME_PAYMENT_ID, entry.id)

            entry.lightningQuoteId?.let {
                putExtra(PaymentRequestActivity.EXTRA_LIGHTNING_QUOTE_ID, it)
            }
            entry.lightningMintUrl?.let {
                putExtra(PaymentRequestActivity.EXTRA_LIGHTNING_MINT_URL, it)
            }
            entry.lightningInvoice?.let {
                putExtra(PaymentRequestActivity.EXTRA_LIGHTNING_INVOICE, it)
            }
            entry.nostrSecretHex?.let {
                putExtra(PaymentRequestActivity.EXTRA_NOSTR_SECRET_HEX, it)
            }
            entry.nostrNprofile?.let {
                putExtra(PaymentRequestActivity.EXTRA_NOSTR_NPROFILE, it)
            }
        }
    }

    fun createTransactionDetailIntent(
        context: Context,
        entry: PaymentHistoryEntry,
        position: Int,
    ): Intent {
        return Intent(context, TransactionDetailActivity::class.java).apply {
            putExtra(TransactionDetailActivity.EXTRA_TRANSACTION_TOKEN, entry.token)
            putExtra(TransactionDetailActivity.EXTRA_TRANSACTION_AMOUNT, entry.amount)
            putExtra(TransactionDetailActivity.EXTRA_TRANSACTION_DATE, entry.date.time)
            putExtra(TransactionDetailActivity.EXTRA_TRANSACTION_UNIT, entry.getUnit())
            putExtra(TransactionDetailActivity.EXTRA_TRANSACTION_ENTRY_UNIT, entry.getEntryUnit())
            putExtra(TransactionDetailActivity.EXTRA_TRANSACTION_ENTERED_AMOUNT, entry.enteredAmount)
            entry.bitcoinPrice?.let {
                putExtra(TransactionDetailActivity.EXTRA_TRANSACTION_BITCOIN_PRICE, it)
            }
            putExtra(TransactionDetailActivity.EXTRA_TRANSACTION_MINT_URL, entry.mintUrl)
            putExtra(TransactionDetailActivity.EXTRA_TRANSACTION_PAYMENT_REQUEST, entry.paymentRequest)
            putExtra(TransactionDetailActivity.EXTRA_TRANSACTION_POSITION, position)
            putExtra(TransactionDetailActivity.EXTRA_TRANSACTION_PAYMENT_TYPE, entry.paymentType)
            putExtra(TransactionDetailActivity.EXTRA_TRANSACTION_LIGHTNING_INVOICE, entry.lightningInvoice)
            putExtra(TransactionDetailActivity.EXTRA_CHECKOUT_BASKET_JSON, entry.checkoutBasketJson)
            putExtra(TransactionDetailActivity.EXTRA_BASKET_ID, entry.basketId)
            putExtra(TransactionDetailActivity.EXTRA_TRANSACTION_TIP_AMOUNT, entry.tipAmountSats)
            putExtra(TransactionDetailActivity.EXTRA_TRANSACTION_TIP_PERCENTAGE, entry.tipPercentage)
        }
    }
}
