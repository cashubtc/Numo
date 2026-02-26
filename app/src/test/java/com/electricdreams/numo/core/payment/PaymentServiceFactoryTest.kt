package com.electricdreams.numo.core.payment

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.electricdreams.numo.core.payment.impl.BTCPayPaymentService
import com.electricdreams.numo.core.payment.impl.LocalPaymentService
import com.electricdreams.numo.core.prefs.PreferenceStore
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PaymentServiceFactoryTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // Initialize singletons if needed
        // CashuWalletManager.init(context) // Might need mocking if it does network or complex stuff
    }

    @Test
    fun `returns LocalPaymentService when btcpay is disabled`() {
        val prefs = PreferenceStore.app(context)
        prefs.putBoolean("btcpay_enabled", false)

        val service = PaymentServiceFactory.create(context)
        assertTrue(service is LocalPaymentService)
    }

    @Test
    fun `returns BtcPayPaymentService when btcpay is enabled and config is valid`() {
        val prefs = PreferenceStore.app(context)
        prefs.putBoolean("btcpay_enabled", true)
        prefs.putString("btcpay_server_url", "https://btcpay.example.com")
        prefs.putString("btcpay_api_key", "secret-key")
        prefs.putString("btcpay_store_id", "store-id")

        val service = PaymentServiceFactory.create(context)
        assertTrue(service is BTCPayPaymentService)
    }

    @Test
    fun `falls back to LocalPaymentService when btcpay is enabled but config is missing`() {
        val prefs = PreferenceStore.app(context)
        prefs.putBoolean("btcpay_enabled", true)
        prefs.putString("btcpay_server_url", "") // Missing URL

        val service = PaymentServiceFactory.create(context)
        assertTrue("Should fallback if URL is empty", service is LocalPaymentService)
    }
}
