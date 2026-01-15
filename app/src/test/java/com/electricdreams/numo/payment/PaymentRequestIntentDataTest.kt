package com.electricdreams.numo.payment

import android.content.Intent
import com.electricdreams.numo.PaymentRequestActivity
import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.feature.tips.TipSelectionActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PaymentRequestIntentDataTest {

    @Test
    fun `fromIntent populates defaults when optional extras missing`() {
        val amount = Amount(42_000, Amount.Currency.BTC)
        val intent = Intent().apply {
            putExtra(PaymentRequestActivity.EXTRA_PAYMENT_AMOUNT, amount.value)
        }

        val data = PaymentRequestIntentData.fromIntent(intent)

        assertEquals(amount.value, data.paymentAmount)
        assertEquals(amount.toString(), data.formattedAmount)
        assertEquals(0, data.tipAmountSats)
        assertEquals(0, data.tipPercentage)
        assertTrue(!data.isResumingPayment)
    }

    @Test
    fun `fromIntent reads tip data when provided`() {
        val intent = Intent().apply {
            putExtra(PaymentRequestActivity.EXTRA_PAYMENT_AMOUNT, 1000L)
            putExtra(PaymentRequestActivity.EXTRA_FORMATTED_AMOUNT, "₿1,000")
            putExtra(TipSelectionActivity.EXTRA_TIP_AMOUNT_SATS, 200L)
            putExtra(TipSelectionActivity.EXTRA_TIP_PERCENTAGE, 20)
            putExtra(TipSelectionActivity.EXTRA_BASE_AMOUNT_SATS, 800L)
            putExtra(TipSelectionActivity.EXTRA_BASE_FORMATTED_AMOUNT, "₿800")
            putExtra(PaymentRequestActivity.EXTRA_RESUME_PAYMENT_ID, "pending123")
        }

        val data = PaymentRequestIntentData.fromIntent(intent)

        assertEquals(200L, data.tipAmountSats)
        assertEquals(20, data.tipPercentage)
        assertEquals(800L, data.baseAmountSats)
        assertEquals("₿800", data.baseFormattedAmount)
        assertTrue(data.isResumingPayment)
    }
}
