package com.electricdreams.numo

import android.widget.TextView
import com.electricdreams.numo.core.network.ConnectivityMonitor
import com.electricdreams.numo.core.payment.impl.BTCPayPaymentService
import com.electricdreams.numo.payment.PaymentTabManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PaymentRequestActivityOfflineStatusTest {
    private lateinit var activity: PaymentRequestActivity
    private lateinit var status: TextView
    private val monitor = mock<ConnectivityMonitor>()

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(PaymentRequestActivity::class.java).get()
        status = TextView(activity)
        val tabs = mock<PaymentTabManager>()
        whenever(tabs.getCurrentTab()).thenReturn(PaymentTabManager.PaymentTab.UNIFIED)
        whenever(monitor.isOnlineNow()).thenReturn(true)
        ReflectionHelpers.setStaticField(ConnectivityMonitor::class.java, "instance", monitor)
        ReflectionHelpers.setField(activity, "statusText", status)
        ReflectionHelpers.setField(activity, "tabManager", tabs)
        ReflectionHelpers.setField(activity, "isDualQuoteCheckout", true)
        ReflectionHelpers.setField(activity, "paymentAmount", 1_000L)
        ReflectionHelpers.setField(activity, "lightningInvoice", "lnbc1saved")
        ReflectionHelpers.setField(activity, "arkoorAddress", "ark1saved")
    }

    @After
    fun tearDown() {
        ReflectionHelpers.setStaticField(ConnectivityMonitor::class.java, "instance", null)
    }

    @Test
    fun `unified payment keeps its request while status follows disconnect and reconnect`() {
        ReflectionHelpers.callInstanceMethod<Void>(activity, "updatePaymentMethodStatus")
        assertEquals("Lightning · Arkoor", status.text.toString())
        val request = ReflectionHelpers.callInstanceMethod<String>(activity, "unifiedPaymentRequest")

        whenever(monitor.isOnlineNow()).thenReturn(false)
        refreshStatus()
        assertEquals(activity.getString(R.string.payment_request_offline_note), status.text.toString())
        assertEquals(request, ReflectionHelpers.callInstanceMethod<String>(activity, "unifiedPaymentRequest"))

        whenever(monitor.isOnlineNow()).thenReturn(true)
        refreshStatus()
        assertEquals("Lightning · Arkoor", status.text.toString())
        assertEquals(request, ReflectionHelpers.callInstanceMethod<String>(activity, "unifiedPaymentRequest"))
    }

    @Test
    fun `connectivity change preserves error and result messages`() {
        whenever(monitor.isOnlineNow()).thenReturn(false)
        for (message in listOf("Payment failed", activity.getString(R.string.payment_request_status_success))) {
            status.text = message
            refreshStatus()
            assertEquals(message, status.text.toString())
        }
    }

    @Test
    fun `btcpay waiting status keeps its existing offline behavior`() {
        ReflectionHelpers.setField(activity, "isDualQuoteCheckout", false)
        ReflectionHelpers.setField(activity, "paymentService", mock<BTCPayPaymentService>())
        whenever(monitor.isOnlineNow()).thenReturn(false)
        val waiting = activity.getString(R.string.payment_request_status_waiting_for_payment)
        status.text = waiting
        refreshStatus()
        assertEquals(waiting, status.text.toString())
    }

    private fun refreshStatus() {
        ReflectionHelpers.callInstanceMethod<Void>(activity, "refreshWaitingStatus")
    }
}
