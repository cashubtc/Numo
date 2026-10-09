package com.electricdreams.numo.payment

import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.electricdreams.numo.PaymentRequestActivity
import com.electricdreams.numo.core.data.model.PaymentHistoryEntry
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
    fun `resume intent preserves sats base unit when amount was entered in fiat`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val entry = PaymentHistoryEntry.createPending(
            amount = 1_000L, entryUnit = "usd", enteredAmount = 100L,
            bitcoinPrice = 100_000.0, paymentRequest = null, formattedAmount = "$1.00",
            ecashUnit = "sat",
        )

        val intent = PaymentIntentFactory.createResumePaymentIntent(context, entry)

        assertEquals("sat", intent.getStringExtra(PaymentRequestActivity.EXTRA_PAYMENT_UNIT))
        assertEquals(1_000L, intent.getLongExtra(PaymentRequestActivity.EXTRA_PAYMENT_AMOUNT, -1))
    }

    @Test
    fun `resume intent preserves non-satoshi base unit`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val entry = PaymentHistoryEntry.createPending(
            amount = 100L, entryUnit = "usd", enteredAmount = 100L,
            bitcoinPrice = null, paymentRequest = null, formattedAmount = "$1.00",
            ecashUnit = "usd",
        )

        val intent = PaymentIntentFactory.createResumePaymentIntent(context, entry)

        assertEquals("usd", intent.getStringExtra(PaymentRequestActivity.EXTRA_PAYMENT_UNIT))
        assertEquals(100L, intent.getLongExtra(PaymentRequestActivity.EXTRA_PAYMENT_AMOUNT, -1))
    }

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

        val intent = decision.buildIntent(context, amount = 42L, formattedAmount = "42 sats", checkoutBasketJson = "{}")

        val component = intent.component
        assertNotNull(component)
        assertEquals(ComponentName(context, PaymentRequestActivity::class.java), component)
        assertEquals(42L, intent.getLongExtra(PaymentRequestActivity.EXTRA_PAYMENT_AMOUNT, -1))
        assertEquals("42 sats", intent.getStringExtra(PaymentRequestActivity.EXTRA_FORMATTED_AMOUNT))
        assertEquals("{}", intent.getStringExtra(PaymentRequestActivity.EXTRA_CHECKOUT_BASKET_JSON))
    }
}
