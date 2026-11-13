package com.electricdreams.numo

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.electricdreams.numo.core.network.ConnectivityMonitor
import com.electricdreams.numo.payment.PaymentTabManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PaymentRequestActivityArkoorStatusTest {
    private lateinit var activity: PaymentRequestActivity
    private lateinit var status: TextView

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(PaymentRequestActivity::class.java).get()
        status = TextView(activity)
        val monitor = mock<ConnectivityMonitor>()
        whenever(monitor.isOnlineNow()).thenReturn(true)
        ReflectionHelpers.setStaticField(ConnectivityMonitor::class.java, "instance", monitor)
        val tabs = mock<PaymentTabManager>()
        whenever(tabs.getCurrentTab()).thenReturn(PaymentTabManager.PaymentTab.CASHU)
        ReflectionHelpers.setField(activity, "statusText", status)
        ReflectionHelpers.setField(activity, "tabManager", tabs)
        ReflectionHelpers.setField(activity, "isDualQuoteCheckout", true)
        ReflectionHelpers.setField(activity, "paymentAmount", 1_000L)
        ReflectionHelpers.setField(activity, "lightningInvoice", "lnbc1test")
        ReflectionHelpers.setField(activity, "arkoorLoading", true)
        ReflectionHelpers.setField(activity, "unifiedQrImageView", ImageView(activity))
        ReflectionHelpers.setField(activity, "unifiedLoadingSpinner", View(activity))
        ShadowToast.reset()
        // Keep NFC out of this status rendering test.
        whenever(tabs.getCurrentTab()).thenReturn(PaymentTabManager.PaymentTab.LIGHTNING)
    }

    @After
    fun tearDown() {
        ReflectionHelpers.setStaticField(ConnectivityMonitor::class.java, "instance", null)
    }

    @Test
    fun `unavailable arkoor silently releases the lightning QR`() {
        unavailable(null)
        assertEquals(0, ShadowToast.shownToastCount())
        assertFalse(ReflectionHelpers.getField(activity, "arkoorLoading"))
        assertNotNull(ReflectionHelpers.callInstanceMethod<String>(activity, "unifiedPaymentRequest"))
        assertEquals("lnbc1test", ReflectionHelpers.getField<String>(activity, "lightningInvoice"))
    }

    @Test
    fun `real arkoor failure remains inline after payment methods refresh`() {
        val message = activity.getString(R.string.payment_request_arkoor_failed, "offline")
        unavailable(message)
        val tabs = mock<PaymentTabManager>()
        whenever(tabs.getCurrentTab()).thenReturn(PaymentTabManager.PaymentTab.UNIFIED)
        ReflectionHelpers.setField(activity, "tabManager", tabs)
        repeat(2) {
            ReflectionHelpers.callInstanceMethod<Void>(activity, "updatePaymentMethodStatus")
            assertEquals("Lightning\n$message", status.text.toString())
        }
        assertEquals(0, ShadowToast.shownToastCount())
    }

    private fun unavailable(message: String?) {
        PaymentRequestActivity::class.java.getDeclaredMethod(
            "onArkoorUnavailable", String::class.java,
        ).apply { isAccessible = true }.invoke(activity, message)
    }
}
