package com.electricdreams.numo.payment

import android.content.Context
import android.content.Intent
import com.electricdreams.numo.PaymentRequestActivity
import com.electricdreams.numo.feature.tips.TipSelectionActivity

/**
 * Pure routing core for deciding which screen to launch during checkout.
 */
object PaymentRoutingCore {

    enum class TargetActivity { TIP_SELECTION, PAYMENT_REQUEST }

    data class RoutingDecision(
        val targetActivity: TargetActivity
    ) {
        fun buildIntent(
            context: Context,
            amount: Long,
            formattedAmount: String,
            checkoutBasketJson: String?
        ): Intent {
            val targetClass = when (targetActivity) {
                TargetActivity.TIP_SELECTION -> TipSelectionActivity::class.java
                TargetActivity.PAYMENT_REQUEST -> PaymentRequestActivity::class.java
            }
            return Intent(context, targetClass).apply {
                putExtra(PaymentRequestActivity.EXTRA_PAYMENT_AMOUNT, amount)
                putExtra(PaymentRequestActivity.EXTRA_FORMATTED_AMOUNT, formattedAmount)
                checkoutBasketJson?.let {
                    putExtra(PaymentRequestActivity.EXTRA_CHECKOUT_BASKET_JSON, it)
                }
            }
        }
    }

    fun determinePaymentRoute(tipsEnabled: Boolean): RoutingDecision =
        if (tipsEnabled) {
            RoutingDecision(TargetActivity.TIP_SELECTION)
        } else {
            RoutingDecision(TargetActivity.PAYMENT_REQUEST)
        }
}
