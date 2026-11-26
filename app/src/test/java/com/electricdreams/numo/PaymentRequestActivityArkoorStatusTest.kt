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
        unavailable()
        assertEquals(0, ShadowToast.shownToastCount())
        assertFalse(ReflectionHelpers.getField(activity, "arkoorLoading"))
        assertNotNull(ReflectionHelpers.callInstanceMethod<String>(activity, "unifiedPaymentRequest"))
        assertEquals("lnbc1test", ReflectionHelpers.getField<String>(activity, "lightningInvoice"))
    }

    @Test
    fun `failed arkoor leaves the status line hidden`() {
        unavailable()
        val tabs = mock<PaymentTabManager>()
        whenever(tabs.getCurrentTab()).thenReturn(PaymentTabManager.PaymentTab.UNIFIED)
        ReflectionHelpers.setField(activity, "tabManager", tabs)
        repeat(2) {
            ReflectionHelpers.callInstanceMethod<Void>(activity, "updatePaymentMethodStatus")
            assertEquals(View.GONE, status.visibility)
            assertEquals("", status.text.toString())
        }
        assertEquals(0, ShadowToast.shownToastCount())
    }

    @Test
    fun `fiat charge keeps the entered amount and shows sats beneath it`() {
        val converted = convertedAmountFor("$1.23")
        assertEquals(View.VISIBLE, converted.visibility)
        assertEquals("1,000 sat", converted.text.toString())
    }

    @Test
    fun `sat charge without an exchange rate shows no duplicate amount`() {
        assertEquals(View.GONE, convertedAmountFor("1,000 sat").visibility)
    }

    private fun convertedAmountFor(formattedAmount: String): TextView {
        val converted = TextView(activity)
        ReflectionHelpers.setField(activity, "convertedAmountDisplay", converted)
        PaymentRequestActivity::class.java.getDeclaredMethod(
            "updateConvertedAmount", String::class.java,
        ).apply { isAccessible = true }.invoke(activity, formattedAmount)
        return converted
    }

    private fun unavailable() {
        PaymentRequestActivity::class.java.getDeclaredMethod("onArkoorUnavailable")
            .apply { isAccessible = true }.invoke(activity)
    }
}
