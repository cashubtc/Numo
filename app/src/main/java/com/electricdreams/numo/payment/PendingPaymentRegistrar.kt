package com.electricdreams.numo.payment

import android.content.Context
import android.util.Log
import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.core.model.Amount.Currency
import com.electricdreams.numo.core.worker.BitcoinPriceWorker
import com.electricdreams.numo.feature.history.PaymentsHistoryActivity

class PendingPaymentRegistrar(
    private val context: Context,
    private val bitcoinPriceWorker: BitcoinPriceWorker?,
    private val store: PendingPaymentStore = PendingPaymentStore.Default
) {
    fun registerPendingPayment(
        paymentAmount: Long,
        formattedAmountString: String,
        tipAmountSats: Long,
        tipPercentage: Int,
        baseAmountSats: Long,
        baseFormattedAmount: String?,
        checkoutBasketJson: String?,
        savedBasketId: String?,
    ): String? {
        val (entryUnit, enteredAmount) = determineEntryAmount(
            formattedAmountString,
            tipAmountSats,
            baseAmountSats,
            baseFormattedAmount
        )

        val bitcoinPrice = bitcoinPriceWorker?.getCurrentPrice()?.takeIf { it > 0 }

        val pendingId = store.addPendingPayment(
            context = context,
            amount = paymentAmount,
            entryUnit = entryUnit,
            enteredAmount = enteredAmount,
            bitcoinPrice = bitcoinPrice,
            paymentRequest = null,
            formattedAmount = formattedAmountString,
            checkoutBasketJson = checkoutBasketJson,
            basketId = savedBasketId,
            tipAmountSats = tipAmountSats,
            tipPercentage = tipPercentage,
        )

        Log.d(TAG, "✅ CREATED PENDING PAYMENT: id=$pendingId")
        Log.d(TAG, "   💰 Total amount: $paymentAmount sats")
        Log.d(TAG, "   📊 Base amount: $enteredAmount $entryUnit")
        Log.d(TAG, "   💸 Tip: $tipAmountSats sats ($tipPercentage%)")
        Log.d(TAG, "   🛒 Has basket: ${checkoutBasketJson != null}")
        Log.d(TAG, "   📱 Formatted: $formattedAmountString")
        return pendingId
    }

    private fun determineEntryAmount(
        formattedAmountString: String,
        tipAmountSats: Long,
        baseAmountSats: Long,
        baseFormattedAmount: String?,
    ): Pair<String, Long> {
        if (tipAmountSats > 0 && baseAmountSats > 0) {
            val parsedBase = baseFormattedAmount?.let { Amount.parse(it) }
            if (parsedBase != null) {
                val entryUnit = if (parsedBase.currency == Currency.BTC) "sat" else parsedBase.currency.name
                return entryUnit to parsedBase.value
            }
            return "sat" to baseAmountSats
        }

        val parsedAmount = Amount.parse(formattedAmountString)
        return if (parsedAmount != null) {
            val entryUnit = if (parsedAmount.currency == Currency.BTC) "sat" else parsedAmount.currency.name
            entryUnit to parsedAmount.value
        } else {
            val fallback = baseAmountSats.takeIf { it > 0 } ?: 0L
            "sat" to fallback
        }
    }

    companion object {
        private const val TAG = "PendingPaymentRegistrar"
    }
}

fun interface PendingPaymentStore {
    fun addPendingPayment(
        context: Context,
        amount: Long,
        entryUnit: String,
        enteredAmount: Long,
        bitcoinPrice: Double?,
        paymentRequest: String?,
        formattedAmount: String,
        checkoutBasketJson: String?,
        basketId: String?,
        tipAmountSats: Long,
        tipPercentage: Int,
    ): String?

    object Default : PendingPaymentStore {
        override fun addPendingPayment(
            context: Context,
            amount: Long,
            entryUnit: String,
            enteredAmount: Long,
            bitcoinPrice: Double?,
            paymentRequest: String?,
            formattedAmount: String,
            checkoutBasketJson: String?,
            basketId: String?,
            tipAmountSats: Long,
            tipPercentage: Int,
        ): String? {
            return PaymentsHistoryActivity.addPendingPayment(
                context = context,
                amount = amount,
                entryUnit = entryUnit,
                enteredAmount = enteredAmount,
                bitcoinPrice = bitcoinPrice,
                paymentRequest = paymentRequest,
                formattedAmount = formattedAmount,
                checkoutBasketJson = checkoutBasketJson,
                basketId = basketId,
                tipAmountSats = tipAmountSats,
                tipPercentage = tipPercentage,
            )
        }
    }
}
