package com.electricdreams.numo.payment

import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.electricdreams.numo.PaymentRequestActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PaymentRoutingCoreTest {

    @Test
    fun `determinePaymentRoute returns tip selection when tips enabled`() {
        val decision = PaymentRoutingCore.determinePaymentRoute(tipsEnabled = true)

        assertEquals(PaymentRoutingCore.TargetActivity.TIP_SELECTION, decision.targetActivity)
    }

    @Test
    fun `determinePaymentRoute returns payment request when tips disabled`() {
        val decision = PaymentRoutingCore.determinePaymentRoute(tipsEnabled = false)

        assertEquals(PaymentRoutingCore.TargetActivity.PAYMENT_REQUEST, decision.targetActivity)
    }

    @Test
    fun `buildIntent sets extras consistently`() {
        val decision = PaymentRoutingCore.RoutingDecision(PaymentRoutingCore.TargetActivity.PAYMENT_REQUEST)
        val context: Context = ApplicationProvider.getApplicationContext()

        val intent = decision.buildIntent(
            context = context,
            amount = 42L,
            paymentUnit = "points",
            formattedAmount = "42 POINTS",
            checkoutBasketJson = "{}",
            paymentIssuerScope = "https://mint.example",
        )

        val component = intent.component
        assertNotNull(component)
        assertEquals(ComponentName(context, PaymentRequestActivity::class.java), component)
        assertEquals(42L, intent.getLongExtra(PaymentRequestActivity.EXTRA_PAYMENT_AMOUNT, -1))
        assertEquals("points", intent.getStringExtra(PaymentRequestActivity.EXTRA_PAYMENT_UNIT))
        assertEquals(
            "https://mint.example",
            intent.getStringExtra(PaymentRequestActivity.EXTRA_PAYMENT_ISSUER_SCOPE),
        )
        assertEquals("42 POINTS", intent.getStringExtra(PaymentRequestActivity.EXTRA_FORMATTED_AMOUNT))
        assertEquals("{}", intent.getStringExtra(PaymentRequestActivity.EXTRA_CHECKOUT_BASKET_JSON))
    }
}
