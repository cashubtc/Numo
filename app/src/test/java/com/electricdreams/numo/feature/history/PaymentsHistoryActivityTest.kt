package com.electricdreams.numo.feature.history

import android.content.Context
import com.electricdreams.numo.core.data.model.PaymentHistoryEntry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PaymentsHistoryActivityTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        // Clear history before each test
        val prefs = context.getSharedPreferences("PaymentHistory", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }

    @Test
    fun `addPendingPayment creates pending entry`() {
        val paymentId = PaymentsHistoryActivity.addPendingPayment(
            context = context,
            amount = 1_000L,
            entryUnit = "sat",
            enteredAmount = 1_000L,
            bitcoinPrice = 50_000.0,
            paymentRequest = "lnbc1...",
            formattedAmount = "₿0.00001000",
        )

        val history = PaymentsHistoryActivity.getPaymentHistory(context)
        assertEquals(1, history.size)
        val entry = history.first()

        assertEquals(paymentId, entry.id)
        assertTrue(entry.isPending())
        assertEquals(1_000L, entry.amount)
        assertEquals("sat", entry.getEntryUnit())
        assertEquals(50_000.0, entry.bitcoinPrice!!, 0.0001)
    }

    @Test
    fun `completePendingPayment marks entry completed and sets token`() {
        val paymentId = PaymentsHistoryActivity.addPendingPayment(
            context = context,
            amount = 500L,
            entryUnit = "sat",
            enteredAmount = 500L,
            bitcoinPrice = null,
            paymentRequest = null,
            formattedAmount = null,
        )

        val token = "cashuA.token.example"
        val mintUrl = "https://mint.example.com"

        PaymentsHistoryActivity.completePendingPayment(
            context = context,
            paymentId = paymentId,
            token = token,
            paymentType = PaymentHistoryEntry.TYPE_CASHU,
            mintUrl = mintUrl,
        )

        val history = PaymentsHistoryActivity.getPaymentHistory(context)
        assertEquals(1, history.size)
        val entry = history.first()

        assertFalse(entry.isPending())
        assertTrue(entry.isCompleted())
        assertEquals(token, entry.token)
        assertEquals(mintUrl, entry.mintUrl)
    }

    @Test
    fun `cancelPendingPayment removes only pending entries`() {
        val id1 = PaymentsHistoryActivity.addPendingPayment(
            context = context,
            amount = 100L,
            entryUnit = "sat",
            enteredAmount = 100L,
            bitcoinPrice = null,
            paymentRequest = null,
            formattedAmount = null,
        )

        val id2 = PaymentsHistoryActivity.addPendingPayment(
            context = context,
            amount = 200L,
            entryUnit = "sat",
            enteredAmount = 200L,
            bitcoinPrice = null,
            paymentRequest = null,
            formattedAmount = null,
        )

        // Complete first payment so it should NOT be removed
        PaymentsHistoryActivity.completePendingPayment(
            context = context,
            paymentId = id1,
            token = "token1",
            paymentType = PaymentHistoryEntry.TYPE_CASHU,
            mintUrl = null,
        )

        PaymentsHistoryActivity.cancelPendingPayment(context, id2)

        val history = PaymentsHistoryActivity.getPaymentHistory(context)
        assertEquals(1, history.size)
        val remaining = history.first()
        assertEquals(id1, remaining.id)
        assertFalse(remaining.isPending())
    }

    @Test
    fun `updatePendingWithTipInfo updates amount and tip fields`() {
        val id = PaymentsHistoryActivity.addPendingPayment(
            context = context,
            amount = 1_000L,
            entryUnit = "sat",
            enteredAmount = 1_000L,
            bitcoinPrice = null,
            paymentRequest = null,
            formattedAmount = null,
        )

        PaymentsHistoryActivity.updatePendingWithTipInfo(
            context = context,
            paymentId = id,
            tipAmountSats = 200L,
            tipPercentage = 20,
            newTotalAmount = 1_200L,
        )

        val history = PaymentsHistoryActivity.getPaymentHistory(context)
        assertEquals(1, history.size)
        val entry = history.first()

        assertEquals(1_200L, entry.amount)
        assertEquals(200L, entry.tipAmountSats)
        assertEquals(20, entry.tipPercentage)
    }
}
