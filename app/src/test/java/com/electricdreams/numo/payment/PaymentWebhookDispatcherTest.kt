package com.electricdreams.numo.payment

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.electricdreams.numo.core.data.model.PaymentHistoryEntry
import com.electricdreams.numo.core.model.CheckoutBasket
import com.electricdreams.numo.core.model.CheckoutBasketItem
import com.electricdreams.numo.core.util.WebhookSettingsManager
import com.google.gson.Gson
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
import java.util.Date

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
            endpointProvider = {
                listOf(
                    endpointConfig(
                        url = endpoint,
                        authKey = "Bearer test-secret",
                    ),
                )
            },
            ioDispatcher = Dispatchers.IO,
            retryDelaysMs = listOf(0L),
        )

        val result = dispatcher.dispatchPaymentReceivedNow(sampleEntry())
        val request = server.takeRequest()

        assertEquals(1, result.totalEndpoints)
        assertEquals(1, result.successCount)
        assertEquals("POST", request.method)
        assertEquals("payment.received", request.getHeader("X-Numo-Event"))
        assertEquals("Bearer test-secret", request.getHeader("Authorization"))

        val body = request.body.readUtf8()
        assertTrue(body.contains("\"event\":\"payment.received\""))
        assertTrue(body.contains("\"payloadVersion\":2"))
        assertTrue(body.contains("\"paymentType\":\"cashu\""))
        assertTrue(body.contains("\"amountSats\":2100"))
        assertTrue(body.contains("\"baseAmountSats\":2000"))
        assertTrue(body.contains("\"entryUnit\":\"USD\""))
        assertTrue(body.contains("\"checkout\":{"))
        assertTrue(body.contains("\"checkoutBasketId\":\"checkout-basket-1\""))
        assertTrue(body.contains("\"items\":[{"))
        assertTrue(body.contains("\"itemId\":\"item-123\""))
        assertTrue(body.contains("\"swapToLightningMint\":{"))
        assertTrue(body.contains("\"meltQuoteId\":\"melt-q-1\""))
        assertTrue(!body.contains("\"token\""))
    }

    @Test
    fun `dispatch retries on failure and succeeds on later attempt`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(200))

        val endpoint = server.url("/retry").toString()
        val dispatcher = PaymentWebhookDispatcher(
            context = context,
            endpointProvider = { listOf(endpointConfig(endpoint)) },
            ioDispatcher = Dispatchers.IO,
            retryDelaysMs = listOf(0L, 0L),
        )

        val result = dispatcher.dispatchPaymentReceivedNow(sampleEntry())

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

        val result = dispatcher.dispatchPaymentReceivedNow(sampleEntry())

        assertEquals(0, result.totalEndpoints)
        assertEquals(0, result.successCount)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `dispatch includes null checkout when basket absent`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))

        val endpoint = server.url("/no-checkout").toString()
        val dispatcher = PaymentWebhookDispatcher(
            context = context,
            endpointProvider = { listOf(endpointConfig(endpoint)) },
            ioDispatcher = Dispatchers.IO,
            retryDelaysMs = listOf(0L),
        )

        val result = dispatcher.dispatchPaymentReceivedNow(sampleEntry(checkoutBasketJson = null))
        val request = server.takeRequest()
        val body = request.body.readUtf8()

        assertEquals(1, result.totalEndpoints)
        assertEquals(null, request.getHeader("Authorization"))
        assertTrue(body.contains("\"transaction\":{"))
        assertTrue(!body.contains("\"checkout\":{"))
    }

    private fun endpointConfig(
        url: String,
        authKey: String? = null,
    ): WebhookSettingsManager.WebhookEndpointConfig {
        return WebhookSettingsManager.WebhookEndpointConfig(
            url = url,
            authKey = authKey,
        )
    }

    private fun sampleEntry(checkoutBasketJson: String? = sampleCheckoutBasketJson()): PaymentHistoryEntry {
        val swapFrame = PaymentHistoryEntry.Companion.SwapToLightningMintFrame(
            unknownMintUrl = "https://unknown-mint.example.com",
            meltQuoteId = "melt-q-1",
            lightningMintUrl = "https://mint.example.com",
            lightningQuoteId = "lightning-q-1",
        )

        return PaymentHistoryEntry(
            id = "payment-123",
            token = "",
            amount = 2100,
            date = Date(1_706_000_000_000),
            rawUnit = "sat",
            rawEntryUnit = "USD",
            enteredAmount = 500,
            bitcoinPrice = 65000.0,
            mintUrl = "https://mint.example.com",
            paymentRequest = "creqAexample",
            rawStatus = PaymentHistoryEntry.STATUS_COMPLETED,
            paymentType = PaymentHistoryEntry.TYPE_CASHU,
            lightningInvoice = null,
            lightningQuoteId = null,
            lightningMintUrl = null,
            formattedAmount = "\$5.00",
            checkoutBasketJson = checkoutBasketJson,
            basketId = "saved-basket-1",
            tipAmountSats = 100,
            tipPercentage = 5,
            swapToLightningMintJson = Gson().toJson(swapFrame),
        )
    }

    private fun sampleCheckoutBasketJson(): String {
        val basket = CheckoutBasket(
            id = "checkout-basket-1",
            checkoutTimestamp = 1_706_000_000_123,
            items = listOf(
                CheckoutBasketItem(
                    itemId = "item-123",
                    uuid = "uuid-123",
                    name = "Coffee",
                    variationName = "Large",
                    sku = "COF-L",
                    category = "Drinks",
                    quantity = 2,
                    priceType = "FIAT",
                    netPriceCents = 250,
                    priceSats = 0,
                    priceCurrency = "USD",
                    vatEnabled = true,
                    vatRate = 10,
                ),
            ),
            currency = "USD",
            bitcoinPrice = 65000.0,
            totalSatoshis = 2100,
        )

        return basket.toJson()
    }
}
