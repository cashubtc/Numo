package com.electricdreams.numo.feature.history

import android.content.Context
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.electricdreams.numo.R
import com.electricdreams.numo.core.data.model.PaymentHistoryEntry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
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

    @Test
    fun `loadHistory shows all transactions by default`() {
        // Add one pending and one completed transaction
        val pendingId = PaymentsHistoryActivity.addPendingPayment(
            context = context, amount = 100L, entryUnit = "sat", enteredAmount = 100L,
            bitcoinPrice = null, paymentRequest = null, formattedAmount = null
        )

        val completedId = PaymentsHistoryActivity.addPendingPayment(
            context = context, amount = 200L, entryUnit = "sat", enteredAmount = 200L,
            bitcoinPrice = null, paymentRequest = null, formattedAmount = null
        )
        PaymentsHistoryActivity.completePendingPayment(
            context = context, paymentId = completedId, token = "token",
            paymentType = PaymentHistoryEntry.TYPE_CASHU, mintUrl = null
        )

        // Launch the activity
        val controller = Robolectric.buildActivity(PaymentsHistoryActivity::class.java).setup()
        val activity = controller.get()

        val recyclerView = activity.findViewById<RecyclerView>(R.id.history_recycler_view)
        val adapter = recyclerView.adapter

        // Since all are shown by default, adapter should show both transactions (plus 1 for the month header)
        // Expected size = 3 (1 Header + 2 Transactions)
        assertNotNull("Adapter should not be null", adapter)
        assertEquals(3, adapter!!.itemCount)
    }

    @Test
    fun `loadHistory shows only completed transactions when FILTER_PAID is set`() {
        // Change preference to show only completed transactions
        val prefs = context.getSharedPreferences("PaymentHistory", Context.MODE_PRIVATE)
        prefs.edit().putInt("filter_state", 1).apply() // 1 = FILTER_PAID

        // Add one pending and one completed transaction
        val pendingId = PaymentsHistoryActivity.addPendingPayment(
            context = context, amount = 100L, entryUnit = "sat", enteredAmount = 100L,
            bitcoinPrice = null, paymentRequest = null, formattedAmount = null
        )

        val completedId = PaymentsHistoryActivity.addPendingPayment(
            context = context, amount = 200L, entryUnit = "sat", enteredAmount = 200L,
            bitcoinPrice = null, paymentRequest = null, formattedAmount = null
        )
        PaymentsHistoryActivity.completePendingPayment(
            context = context, paymentId = completedId, token = "token",
            paymentType = PaymentHistoryEntry.TYPE_CASHU, mintUrl = null
        )

        // Launch the activity
        val controller = Robolectric.buildActivity(PaymentsHistoryActivity::class.java).setup()
        val activity = controller.get()

        val recyclerView = activity.findViewById<RecyclerView>(R.id.history_recycler_view)
        val adapter = recyclerView.adapter

        // Expected size = 2 (1 Header + 1 Completed Transaction)
        assertNotNull("Adapter should not be null", adapter)
        assertEquals(2, adapter!!.itemCount)
    }

    @Test
    fun `loadHistory shows only pending transactions when FILTER_PENDING is set`() {
        // Change preference to show only pending transactions
        val prefs = context.getSharedPreferences("PaymentHistory", Context.MODE_PRIVATE)
        prefs.edit().putInt("filter_state", 2).apply() // 2 = FILTER_PENDING

        // Add one pending and one completed transaction
        val pendingId = PaymentsHistoryActivity.addPendingPayment(
            context = context, amount = 100L, entryUnit = "sat", enteredAmount = 100L,
            bitcoinPrice = null, paymentRequest = null, formattedAmount = null
        )

        val completedId = PaymentsHistoryActivity.addPendingPayment(
            context = context, amount = 200L, entryUnit = "sat", enteredAmount = 200L,
            bitcoinPrice = null, paymentRequest = null, formattedAmount = null
        )
        PaymentsHistoryActivity.completePendingPayment(
            context = context, paymentId = completedId, token = "token",
            paymentType = PaymentHistoryEntry.TYPE_CASHU, mintUrl = null
        )

        // Launch the activity
        val controller = Robolectric.buildActivity(PaymentsHistoryActivity::class.java).setup()
        val activity = controller.get()

        val recyclerView = activity.findViewById<RecyclerView>(R.id.history_recycler_view)
        val adapter = recyclerView.adapter

        // Expected size = 2 (1 Header + 1 Pending Transaction)
        assertNotNull("Adapter should not be null", adapter)
        assertEquals(2, adapter!!.itemCount)
    }

    @Test
    fun `loadHistory filters by date correctly`() {
        // Change preference to show all statuses, and filter between start/end dates
        val prefs = context.getSharedPreferences("PaymentHistory", Context.MODE_PRIVATE)
        prefs.edit().putInt("filter_state", 0).apply() // 0 = FILTER_ALL
        
        // Let's set the date filter to epoch (1970) to ensure we get no recent transactions
        prefs.edit().putLong("filter_date_start", 1000L).apply()
        prefs.edit().putLong("filter_date_end", 2000L).apply()

        // Add transaction for today
        val todayId = PaymentsHistoryActivity.addPendingPayment(
            context = context, amount = 100L, entryUnit = "sat", enteredAmount = 100L,
            bitcoinPrice = null, paymentRequest = null, formattedAmount = null
        )
        
        // Launch the activity
        val controller = Robolectric.buildActivity(PaymentsHistoryActivity::class.java).setup()
        val activity = controller.get()

        val recyclerView = activity.findViewById<RecyclerView>(R.id.history_recycler_view)
        val adapter = recyclerView.adapter

        // Expected size = 0 transactions matching (only the Header is left, or 0 if no header applies)
        // Wait, adapter always groups. If empty, items.size == 0
        assertNotNull("Adapter should not be null", adapter)
        assertEquals(0, adapter!!.itemCount)
    }
}
