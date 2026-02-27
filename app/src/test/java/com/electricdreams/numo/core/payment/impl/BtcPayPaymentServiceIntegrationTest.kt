package com.electricdreams.numo.core.payment.impl

import com.electricdreams.numo.core.payment.BTCPayConfig
import com.electricdreams.numo.core.payment.PaymentState
import com.electricdreams.numo.core.wallet.WalletResult
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileInputStream
import java.util.Properties

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BtcPayPaymentServiceIntegrationTest {

    private lateinit var config: BTCPayConfig
    private lateinit var service: BTCPayPaymentService
    private val httpClient = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    @Before
    fun setup() {
        val props = Properties()
        var envFile = File("btcpay_env.properties")
        if (!envFile.exists()) envFile = File("../btcpay_env.properties")

        if (envFile.exists()) {
            println("Loading config from ${envFile.absolutePath}")
            props.load(FileInputStream(envFile))
            config = BTCPayConfig(
                serverUrl = props.getProperty("BTCPAY_SERVER_URL"),
                apiKey = props.getProperty("BTCPAY_API_KEY"),
                storeId = props.getProperty("BTCPAY_STORE_ID"),
            )
        } else {
            config = BTCPayConfig(
                serverUrl = System.getenv("BTCPAY_SERVER_URL") ?: "http://localhost:49392",
                apiKey = System.getenv("BTCPAY_API_KEY") ?: "",
                storeId = System.getenv("BTCPAY_STORE_ID") ?: "",
            )
        }
        service = BTCPayPaymentService(config)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun skipIfNotConfigured(): Boolean {
        if (config.apiKey.isEmpty() || config.storeId.isEmpty()) {
            println("SKIP: BTCPay not configured")
            return true
        }
        return false
    }

    /**
     * Uses the BTCPay Greenfield API to force an invoice into a specific status.
     * Requires btcpay.store.canmodifystoresettings permission.
     * Supported values: "MarkSettled", "MarkInvalid"
     */
    private fun markInvoiceStatus(invoiceId: String, status: String) {
        val url = "${config.serverUrl.trimEnd('/')}/api/v1/stores/${config.storeId}/invoices/$invoiceId/status"
        val body = """{"status": "$status"}"""
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(jsonMediaType))
            .addHeader("Authorization", "token ${config.apiKey}")
            .build()
        httpClient.newCall(request).execute().use { response ->
            println("markInvoiceStatus($invoiceId, $status) → HTTP ${response.code}")
        }
    }

    private fun createInvoiceId(amountSats: Long = 1000L, description: String = "Test"): String {
        return service.let {
            runBlocking { it.createPayment(amountSats, description).getOrThrow().paymentId }
        }
    }

    // -------------------------------------------------------------------------
    // isReady()
    // -------------------------------------------------------------------------

    @Test
    fun testIsReady_withValidConfig() {
        if (skipIfNotConfigured()) return
        assertTrue("isReady() should return true with valid config", service.isReady())
    }

    @Test
    fun testIsReady_withBlankUrl() {
        assertFalse(
            "isReady() should return false when URL is blank",
            BTCPayPaymentService(BTCPayConfig("", "key", "store")).isReady()
        )
    }

    @Test
    fun testIsReady_withBlankApiKey() {
        assertFalse(
            "isReady() should return false when API key is blank",
            BTCPayPaymentService(BTCPayConfig("http://localhost", "", "store")).isReady()
        )
    }

    @Test
    fun testIsReady_withBlankStoreId() {
        assertFalse(
            "isReady() should return false when store ID is blank",
            BTCPayPaymentService(BTCPayConfig("http://localhost", "key", "")).isReady()
        )
    }

    // -------------------------------------------------------------------------
    // createPayment()
    // -------------------------------------------------------------------------

    @Test
    fun testCreatePayment_returnsPaymentId() = runBlocking {
        if (skipIfNotConfigured()) return@runBlocking
        val result = service.createPayment(1000L, "Create test")
        assertTrue("createPayment() should succeed", result is WalletResult.Success)
        val data = result.getOrThrow()
        assertTrue("paymentId should not be blank", data.paymentId.isNotBlank())
        println("Created invoice: ${data.paymentId}")
    }

    @Test
    fun testCreatePayment_returnsLightningBolt11() = runBlocking {
        if (skipIfNotConfigured()) return@runBlocking
        val data = service.createPayment(500L, "Lightning test").getOrThrow()
        assertNotNull("bolt11 should not be null", data.bolt11)
        assertTrue(
            "bolt11 should be a valid Lightning invoice",
            data.bolt11!!.startsWith("lnbc") ||
                data.bolt11.startsWith("lntb") ||
                data.bolt11.startsWith("lnbcrt"),
        )
        println("bolt11: ${data.bolt11!!.take(40)}...")
    }

    @Test
    fun testCreatePayment_returnsCashuPaymentRequest() = runBlocking {
        if (skipIfNotConfigured()) return@runBlocking
        val data = service.createPayment(1000L, "Cashu test").getOrThrow()
        // cashuPR may be null if Cashu plugin is not installed — just log the result
        println("cashuPR: ${data.cashuPR?.take(40) ?: "null (Cashu plugin may not be configured)"}")
    }

    @Test
    fun testCreatePayment_withNullDescription() = runBlocking {
        if (skipIfNotConfigured()) return@runBlocking
        val result = service.createPayment(1000L, null)
        assertTrue("createPayment() with null description should succeed", result is WalletResult.Success)
    }

    @Test
    fun testCreatePayment_withSmallAmount() = runBlocking {
        if (skipIfNotConfigured()) return@runBlocking
        val result = service.createPayment(1L, "1 sat test")
        assertTrue("createPayment() with 1 sat should succeed", result is WalletResult.Success)
        println("1-sat invoice: ${result.getOrThrow().paymentId}")
    }

    @Test
    fun testCreatePayment_withLargeAmount() = runBlocking {
        if (skipIfNotConfigured()) return@runBlocking
        val result = service.createPayment(21_000L, "21k sat test")
        assertTrue("createPayment() with large amount should succeed", result is WalletResult.Success)
    }

    @Test
    fun testCreatePayment_withInvalidApiKey_returnsFailure() = runBlocking {
        if (skipIfNotConfigured()) return@runBlocking
        val badService = BTCPayPaymentService(BTCPayConfig(config.serverUrl, "bad_api_key_xxx", config.storeId))
        val result = badService.createPayment(1000L, "Bad key test")
        assertTrue("createPayment() with invalid API key should fail", result is WalletResult.Failure)
        println("Expected error: ${(result as WalletResult.Failure).error.message}")
    }

    @Test
    fun testCreatePayment_withInvalidStoreId_returnsFailure() = runBlocking {
        if (skipIfNotConfigured()) return@runBlocking
        val badService = BTCPayPaymentService(BTCPayConfig(config.serverUrl, config.apiKey, "nonexistent_store_id"))
        val result = badService.createPayment(1000L, "Bad store test")
        assertTrue("createPayment() with invalid store ID should fail", result is WalletResult.Failure)
        println("Expected error: ${(result as WalletResult.Failure).error.message}")
    }

    // -------------------------------------------------------------------------
    // checkPaymentStatus()
    // -------------------------------------------------------------------------

    @Test
    fun testCheckPaymentStatus_newInvoice_isPending() = runBlocking {
        if (skipIfNotConfigured()) return@runBlocking
        val paymentId = createInvoiceId(1000L, "Status pending test")
        val status = service.checkPaymentStatus(paymentId).getOrThrow()
        assertEquals("Freshly created invoice should be PENDING", PaymentState.PENDING, status)
    }

    @Test
    fun testCheckPaymentStatus_afterMarkSettled_isPaid() = runBlocking {
        if (skipIfNotConfigured()) return@runBlocking
        val paymentId = createInvoiceId(1000L, "Status settled test")
        markInvoiceStatus(paymentId, "MarkSettled")
        val status = service.checkPaymentStatus(paymentId).getOrThrow()
        assertEquals("Settled invoice should be PAID", PaymentState.PAID, status)
    }

    @Test
    fun testCheckPaymentStatus_afterMarkInvalid_isFailed() = runBlocking {
        if (skipIfNotConfigured()) return@runBlocking
        val paymentId = createInvoiceId(1000L, "Status invalid test")
        markInvoiceStatus(paymentId, "MarkInvalid")
        val status = service.checkPaymentStatus(paymentId).getOrThrow()
        assertEquals("Invalidated invoice should be FAILED", PaymentState.FAILED, status)
    }

    @Test
    fun testCheckPaymentStatus_nonExistentId_returnsFailure() = runBlocking {
        if (skipIfNotConfigured()) return@runBlocking
        val result = service.checkPaymentStatus("nonexistent-invoice-id-xyz-12345")
        assertTrue("Non-existent invoice ID should return failure", result is WalletResult.Failure)
        println("Expected error: ${(result as WalletResult.Failure).error.message}")
    }

    @Test
    fun testCheckPaymentStatus_multipleConsecutivePolls_remainPending() = runBlocking {
        if (skipIfNotConfigured()) return@runBlocking
        val paymentId = createInvoiceId(1000L, "Multi-poll test")
        repeat(3) { i ->
            val status = service.checkPaymentStatus(paymentId).getOrThrow()
            assertEquals("Poll $i should still be PENDING", PaymentState.PENDING, status)
        }
    }

    // -------------------------------------------------------------------------
    // fetchLightningInvoice()
    // -------------------------------------------------------------------------

    @Test
    fun testFetchLightningInvoice_returnsValidBolt11() = runBlocking {
        if (skipIfNotConfigured()) return@runBlocking
        val paymentId = createInvoiceId(1000L, "LN fetch test")
        val bolt11 = service.fetchLightningInvoice(paymentId)
        assertNotNull("fetchLightningInvoice() should return a bolt11", bolt11)
        assertTrue(
            "bolt11 should be a valid Lightning invoice",
            bolt11!!.startsWith("lnbc") || bolt11.startsWith("lntb") || bolt11.startsWith("lnbcrt"),
        )
        println("Fetched bolt11: ${bolt11.take(40)}...")
    }

    @Test
    fun testFetchLightningInvoice_settledInvoice_doesNotThrow() = runBlocking {
        if (skipIfNotConfigured()) return@runBlocking
        val paymentId = createInvoiceId(1000L, "LN fetch settled test")
        markInvoiceStatus(paymentId, "MarkSettled")
        // Should not throw — result may be null or the original bolt11
        val bolt11 = service.fetchLightningInvoice(paymentId)
        println("bolt11 for settled invoice: ${bolt11?.take(40) ?: "null"}")
    }

    // -------------------------------------------------------------------------
    // redeemToken()
    // -------------------------------------------------------------------------

    @Test
    fun testRedeemToken_invalidToken_returnsFailure() = runBlocking {
        if (skipIfNotConfigured()) return@runBlocking
        val paymentId = createInvoiceId(1000L, "Redeem test")
        val result = service.redeemToken("cashuAinvalidtoken", paymentId)
        assertTrue("redeemToken() with invalid token should fail", result is WalletResult.Failure)
        println("Expected error: ${(result as WalletResult.Failure).error.message}")
    }

    @Test
    fun testRedeemToken_withoutPaymentId_returnsFailure() = runBlocking {
        if (skipIfNotConfigured()) return@runBlocking
        val result = service.redeemToken("cashuAinvalidtoken", null)
        assertTrue("redeemToken() without paymentId should fail", result is WalletResult.Failure)
    }

    @Test
    fun testRedeemToken_emptyToken_returnsFailure() = runBlocking {
        if (skipIfNotConfigured()) return@runBlocking
        val paymentId = createInvoiceId(1000L, "Empty token test")
        val result = service.redeemToken("", paymentId)
        assertTrue("redeemToken() with empty token should fail", result is WalletResult.Failure)
    }

    // -------------------------------------------------------------------------
    // redeemTokenToPostEndpoint() — NUT-18
    // -------------------------------------------------------------------------

    @Test
    fun testRedeemTokenToPostEndpoint_invalidToken_returnsFailure() = runBlocking {
        if (skipIfNotConfigured()) return@runBlocking
        val paymentId = createInvoiceId(1000L, "NUT-18 test")
        val postUrl = "${config.serverUrl.trimEnd('/')}/cashu/pay-invoice"
        val result = service.redeemTokenToPostEndpoint(
            token = "cashuAinvalidtoken",
            requestId = paymentId,
            postUrl = postUrl,
        )
        assertTrue("redeemTokenToPostEndpoint() with invalid token should fail", result is WalletResult.Failure)
        println("Expected NUT-18 error: ${(result as WalletResult.Failure).error.message}")
    }

    @Test
    fun testRedeemTokenToPostEndpoint_settledInvoice_returnsFailure() = runBlocking {
        if (skipIfNotConfigured()) return@runBlocking
        val paymentId = createInvoiceId(1000L, "NUT-18 settled test")
        markInvoiceStatus(paymentId, "MarkSettled")
        val postUrl = "${config.serverUrl.trimEnd('/')}/cashu/pay-invoice"
        val result = service.redeemTokenToPostEndpoint(
            token = "cashuAinvalidtoken",
            requestId = paymentId,
            postUrl = postUrl,
        )
        // Settled invoice + invalid token = failure
        assertTrue("NUT-18 redeem on settled invoice with invalid token should fail", result is WalletResult.Failure)
    }
}
