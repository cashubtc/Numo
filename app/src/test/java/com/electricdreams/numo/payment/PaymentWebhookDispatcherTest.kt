package com.electricdreams.numo.payment

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PaymentWebhookDispatcherTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `dispatch posts payment payload to endpoint`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))

        val endpoint = server.url("/webhook").toString()
        val dispatcher = PaymentWebhookDispatcher(
            context = context,
            endpointProvider = { listOf(endpoint) },
            ioDispatcher = Dispatchers.IO,
            retryDelaysMs = listOf(0L),
        )

        val result = dispatcher.dispatchPaymentReceivedNow(sampleEvent())
        val request = server.takeRequest()

        assertEquals(1, result.totalEndpoints)
        assertEquals(1, result.successCount)
        assertEquals("POST", request.method)
        assertEquals("payment.received", request.getHeader("X-Numo-Event"))

        val body = request.body.readUtf8()
        assertTrue(body.contains("\"event\":\"payment.received\""))
        assertTrue(body.contains("\"paymentType\":\"cashu\""))
        assertTrue(body.contains("\"amountSats\":2100"))
        assertTrue(!body.contains("\"token\""))
    }

    @Test
    fun `dispatch retries on failure and succeeds on later attempt`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(200))

        val endpoint = server.url("/retry").toString()
        val dispatcher = PaymentWebhookDispatcher(
            context = context,
            endpointProvider = { listOf(endpoint) },
            ioDispatcher = Dispatchers.IO,
            retryDelaysMs = listOf(0L, 0L),
        )

        val result = dispatcher.dispatchPaymentReceivedNow(sampleEvent())

        assertEquals(1, result.totalEndpoints)
        assertEquals(1, result.successCount)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `dispatch with no endpoints does nothing`() = runTest {
        val dispatcher = PaymentWebhookDispatcher(
            context = context,
            endpointProvider = { emptyList() },
            ioDispatcher = Dispatchers.IO,
            retryDelaysMs = listOf(0L),
        )

        val result = dispatcher.dispatchPaymentReceivedNow(sampleEvent())

        assertEquals(0, result.totalEndpoints)
        assertEquals(0, result.successCount)
        assertEquals(0, server.requestCount)
    }

    private fun sampleEvent(): PaymentWebhookDispatcher.PaymentReceivedEvent {
        return PaymentWebhookDispatcher.PaymentReceivedEvent(
            paymentId = "payment-123",
            amountSats = 2100,
            paymentType = "cashu",
            status = "completed",
            mintUrl = "https://mint.example.com",
            tipAmountSats = 100,
            tipPercentage = 5,
            basketId = "basket-1",
            lightningInvoice = null,
            lightningQuoteId = null,
            lightningMintUrl = null,
        )
    }
}
