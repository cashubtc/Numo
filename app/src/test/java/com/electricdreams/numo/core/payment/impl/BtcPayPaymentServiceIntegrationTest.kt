package com.electricdreams.numo.core.payment.impl

import android.util.Base64
import com.electricdreams.numo.core.payment.BTCPayConfig
import com.electricdreams.numo.core.payment.PaymentState
import com.electricdreams.numo.core.wallet.WalletResult
import com.google.gson.JsonParser
import com.upokecenter.cbor.CBORObject
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
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
    private var cashuEnabled = false
    private var cdkMintUrl: String = "http://localhost:3338"
    private var customerLndUrl: String = "http://localhost:35532"

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
            cashuEnabled = props.getProperty("CASHU_ENABLED", "false").toBoolean()
            cdkMintUrl = props.getProperty("CDK_MINT_URL", "http://localhost:3338")
            customerLndUrl = props.getProperty("CUSTOMER_LND_URL", "http://localhost:35532")
        } else {
            config = BTCPayConfig(
                serverUrl = System.getenv("BTCPAY_SERVER_URL") ?: "http://localhost:49392",
                apiKey = System.getenv("BTCPAY_API_KEY") ?: "",
                storeId = System.getenv("BTCPAY_STORE_ID") ?: "",
            )
            cashuEnabled = System.getenv("CASHU_ENABLED")?.toBoolean() ?: false
            cdkMintUrl = System.getenv("CDK_MINT_URL") ?: "http://localhost:3338"
            customerLndUrl = System.getenv("CUSTOMER_LND_URL") ?: "http://localhost:35532"
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

    private fun skipIfCashuNotEnabled() {
        assumeFalse("BTCPay not configured — skipping", config.apiKey.isEmpty() || config.storeId.isEmpty())
        assumeFalse("Cashu not enabled — skipping", !cashuEnabled)
    }

    private fun baseUrl(): String = config.serverUrl.trimEnd('/')

    private fun cashuGet(path: String): Pair<Int, String> {
        val url = "${baseUrl()}/api/v1/stores/${config.storeId}/cashu$path"
        val request = Request.Builder()
            .url(url).get()
            .addHeader("Authorization", "token ${config.apiKey}")
            .build()
        return executeHttpRequest(request)
    }

    private fun cashuPut(path: String, body: String): Pair<Int, String> {
        val url = "${baseUrl()}/api/v1/stores/${config.storeId}/cashu$path"
        val request = Request.Builder()
            .url(url).put(body.toRequestBody(jsonMediaType))
            .addHeader("Authorization", "token ${config.apiKey}")
            .build()
        return executeHttpRequest(request)
    }

    private fun cashuPost(path: String, body: String = ""): Pair<Int, String> {
        val url = "${baseUrl()}/api/v1/stores/${config.storeId}/cashu$path"
        val request = Request.Builder()
            .url(url).post(body.toRequestBody(jsonMediaType))
            .addHeader("Authorization", "token ${config.apiKey}")
            .build()
        return executeHttpRequest(request)
    }

    private fun publicPost(path: String, body: String): Pair<Int, String> {
        val request = Request.Builder()
            .url("${baseUrl()}$path")
            .post(body.toRequestBody(jsonMediaType))
            .build()
        return executeHttpRequest(request)
    }

    private fun publicPostForm(url: String, queryParams: String): Pair<Int, String> {
        val request = Request.Builder()
            .url("$url?$queryParams")
            .post("".toRequestBody(jsonMediaType))
            .build()
        return executeHttpRequest(request)
    }

    private fun executeHttpRequest(request: Request): Pair<Int, String> {
        httpClient.newCall(request).execute().use { response ->
            return Pair(response.code, response.body?.string() ?: "")
        }
    }

    // --- CBOR-based cashuPR parsing (avoids CDK native lib dependency) ---

    private fun decodeCashuPR(creq: String): CBORObject? {
        if (!creq.startsWith("creqA")) return null
        val bytes = Base64.decode(creq.removePrefix("creqA"), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return CBORObject.DecodeFromBytes(bytes)
    }

    private fun getIdFromPR(creq: String): String? = decodeCashuPR(creq)?.get("i")?.AsString()

    private fun getPostUrlFromPR(creq: String): String? {
        val transports = decodeCashuPR(creq)?.get("t") ?: return null
        for (i in 0 until transports.size()) {
            val t = transports[i]
            if (t["t"]?.AsString().equals("post", ignoreCase = true)) return t["a"]?.AsString()
        }
        return null
    }

    private fun stripTransportsFromPR(creq: String): String? {
        val cbor = decodeCashuPR(creq) ?: return null
        val map = CBORObject.NewMap()
        for (key in cbor.keys) {
            val k = key.AsString()
            if (k != "t") map.Add(k, cbor[key])
        }
        return "creqA" + Base64.encodeToString(map.EncodeToBytes(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun createInvoiceAndGetCashuPR(amountSats: Long = 1000L): Pair<String, String?> {
        val invoiceId = createInvoiceId(amountSats)
        val url = "${baseUrl()}/api/v1/stores/${config.storeId}/invoices/$invoiceId/payment-methods"
        val request = Request.Builder().url(url).get()
            .addHeader("Authorization", "token ${config.apiKey}").build()
        val (code, body) = executeHttpRequest(request)
        assertEquals("Payment methods request should succeed", 200, code)

        val array = JsonParser.parseString(body).asJsonArray
        var cashuPR: String? = null
        for (element in array) {
            val obj = element.asJsonObject
            val pm = obj.get("paymentMethodId")?.takeIf { !it.isJsonNull }?.asString ?: ""
            if (pm.contains("Cashu", ignoreCase = true)) {
                cashuPR = obj.get("destination")?.takeIf { !it.isJsonNull }?.asString
            }
        }
        return Pair(invoiceId, cashuPR)
    }

    /**
     * Uses the BTCPay Greenfield API to force an invoice into a specific status.
     * Requires btcpay.store.canmodifystoresettings permission.
     * Supported values: "Settled", "Invalid"
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
            check(response.isSuccessful) { "markInvoiceStatus failed: HTTP ${response.code} ${response.body?.string()}"
            }
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
        markInvoiceStatus(paymentId, "Settled")
        val status = service.checkPaymentStatus(paymentId).getOrThrow()
        assertEquals("Settled invoice should be PAID", PaymentState.PAID, status)
    }

    @Test
    fun testCheckPaymentStatus_afterMarkInvalid_isFailed() = runBlocking {
        if (skipIfNotConfigured()) return@runBlocking
        val paymentId = createInvoiceId(1000L, "Status invalid test")
        markInvoiceStatus(paymentId, "Invalid")
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
        markInvoiceStatus(paymentId, "Settled")
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
        markInvoiceStatus(paymentId, "Settled")
        val postUrl = "${config.serverUrl.trimEnd('/')}/cashu/pay-invoice"
        val result = service.redeemTokenToPostEndpoint(
            token = "cashuAinvalidtoken",
            requestId = paymentId,
            postUrl = postUrl,
        )
        // Settled invoice + invalid token = failure
        assertTrue("NUT-18 redeem on settled invoice with invalid token should fail", result is WalletResult.Failure)
    }

    // =========================================================================
    // Cashu Greenfield API — smoke tests
    // =========================================================================

    @Test
    fun testGetCashuConfig_returnsEnabled() {
        skipIfCashuNotEnabled()
        val (code, body) = cashuGet("")
        assertEquals("GET /cashu should return 200", 200, code)
        val json = JsonParser.parseString(body).asJsonObject
        assertTrue("Cashu should be enabled", json.get("enabled").asBoolean)
        assertNotNull("paymentModel should be present", json.get("paymentModel"))
        println("Cashu config: $body")
    }

    @Test
    fun testUpdateCashuConfig_disableAndReEnable() {
        skipIfCashuNotEnabled()
        val (disableCode, disableBody) = cashuPut("", """{"enabled": false}""")
        assertEquals(200, disableCode)
        assertFalse(JsonParser.parseString(disableBody).asJsonObject.get("enabled").asBoolean)
        val (enableCode, enableBody) = cashuPut("", """{"enabled": true}""")
        assertEquals(200, enableCode)
        assertTrue(JsonParser.parseString(enableBody).asJsonObject.get("enabled").asBoolean)
    }

    @Test
    fun testGetWalletBalances_emptyAfterCreation() {
        skipIfCashuNotEnabled()
        val (code, body) = cashuGet("/wallet/balances")
        assertEquals("GET /wallet/balances should return 200", 200, code)
        val balances = JsonParser.parseString(body).asJsonObject.getAsJsonArray("balances")
        assertEquals("Fresh wallet should have no balances", 0, balances.size())
    }

    @Test
    fun testCreateWallet_duplicateFails() {
        skipIfCashuNotEnabled()
        val (code, body) = cashuPost("/wallet")
        assertNotEquals("Duplicate wallet creation should not return 200", 200, code)
        println("Duplicate wallet response (HTTP $code): $body")
    }

    // =========================================================================
    // Cashu — Invoice + Payment Request
    // =========================================================================

    @Test
    fun testCreateInvoice_hasCashuPaymentRequest() {
        skipIfCashuNotEnabled()
        val (invoiceId, cashuPR) = createInvoiceAndGetCashuPR(1000L)
        assertNotNull("Invoice should have a Cashu payment request", cashuPR)
        assertTrue("Cashu PR should start with 'creqA'", cashuPR!!.startsWith("creqA"))
        println("Invoice $invoiceId cashuPR: ${cashuPR.take(50)}...")
    }

    @Test
    fun testCashuPR_canBeParsedForId() {
        skipIfCashuNotEnabled()
        val (_, cashuPR) = createInvoiceAndGetCashuPR(500L)
        assertNotNull("Cashu PR should not be null", cashuPR)
        val id = getIdFromPR(cashuPR!!)
        assertNotNull("Payment request should have an ID", id)
        assertTrue("Payment request ID should not be blank", id!!.isNotBlank())
        println("Cashu PR ID: $id")
    }

    @Test
    fun testCashuPR_hasPostTransport() {
        skipIfCashuNotEnabled()
        val (_, cashuPR) = createInvoiceAndGetCashuPR(500L)
        assertNotNull("Cashu PR should not be null", cashuPR)
        val postUrl = getPostUrlFromPR(cashuPR!!)
        assertNotNull("Payment request should have a POST transport URL", postUrl)
        assertTrue("POST URL should point to pay-invoice-pr", postUrl!!.contains("cashu/pay-invoice-pr"))
        println("Cashu PR POST URL: $postUrl")
    }

    @Test
    fun testCashuPR_strippedTransports() {
        skipIfCashuNotEnabled()
        val (_, cashuPR) = createInvoiceAndGetCashuPR(500L)
        assertNotNull("Cashu PR should not be null", cashuPR)
        val stripped = stripTransportsFromPR(cashuPR!!)
        assertNotNull("stripTransports() should return a valid string", stripped)
        assertTrue("Stripped PR should still start with 'creqA'", stripped!!.startsWith("creqA"))
        assertNull("Stripped PR should not have a POST transport", getPostUrlFromPR(stripped))
        assertEquals("Stripped PR should preserve the payment ID", getIdFromPR(cashuPR), getIdFromPR(stripped))
    }

    @Test
    fun testCashuPR_differentAmountsProduceDifferentRequests() {
        skipIfCashuNotEnabled()
        val (_, pr1) = createInvoiceAndGetCashuPR(100L)
        val (_, pr2) = createInvoiceAndGetCashuPR(5000L)
        assertNotNull(pr1)
        assertNotNull(pr2)
        assertNotEquals("Different amounts should produce different payment requests", pr1, pr2)
        assertNotEquals("Different invoices should have different PR IDs", getIdFromPR(pr1!!), getIdFromPR(pr2!!))
    }

    // =========================================================================
    // Cashu — Token & Transaction API
    // =========================================================================

    @Test
    fun testGetExportedTokens_emptyInitially() {
        skipIfCashuNotEnabled()
        val (code, body) = cashuGet("/tokens")
        assertEquals("GET /tokens should return 200", 200, code)
        assertEquals("Fresh store should have no exported tokens", 0, JsonParser.parseString(body).asJsonArray.size())
    }

    @Test
    fun testGetFailedTransactions_emptyInitially() {
        skipIfCashuNotEnabled()
        val (code, body) = cashuGet("/failed-transactions")
        assertEquals("GET /failed-transactions should return 200", 200, code)
        assertEquals("Fresh store should have no failed transactions", 0, JsonParser.parseString(body).asJsonArray.size())
    }

    // =========================================================================
    // Cashu — Error handling
    // =========================================================================

    @Test
    fun testCashuPayInvoice_invalidToken_returnsBadRequest() {
        skipIfCashuNotEnabled()
        val invoiceId = createInvoiceId(1000L)
        val (code, body) = publicPostForm("${baseUrl()}/cashu/pay-invoice", "token=cashuAinvalidgarbage&invoiceId=$invoiceId")
        assertEquals("Invalid token should return 400", 400, code)
        println("pay-invoice error (HTTP $code): $body")
    }

    @Test
    fun testCashuPayInvoicePr_invalidPayload_returnsBadRequest() {
        skipIfCashuNotEnabled()
        val (code, body) = publicPost("/cashu/pay-invoice-pr", """{"id": "fake-id", "mint": "http://fake-mint", "unit": "sat", "proofs": []}""")
        assertTrue("Invalid NUT-19 payload should return 4xx", code in 400..499)
        println("pay-invoice-pr error (HTTP $code): $body")
    }

    @Test
    fun testCashuPayInvoice_emptyToken_returnsBadRequest() {
        skipIfCashuNotEnabled()
        val invoiceId = createInvoiceId(1000L)
        val (code, _) = publicPostForm("${baseUrl()}/cashu/pay-invoice", "token=&invoiceId=$invoiceId")
        assertEquals("Empty token should return 400", 400, code)
    }

    @Test
    fun testCashuRedeemToken_viaService_invalidToken_returnsFailure() = runBlocking {
        skipIfCashuNotEnabled()
        val invoiceId = createInvoiceId(1000L)
        val result = service.redeemToken("cashuBinvalidtoken", invoiceId)
        assertTrue("redeemToken with invalid token should fail", result is WalletResult.Failure)
    }

    // =========================================================================
    // Cashu — BTCPayPaymentService integration
    // =========================================================================

    @Test
    fun testCashuCreatePayment_returnsCashuPR() = runBlocking {
        skipIfCashuNotEnabled()
        val result = service.createPayment(1000L, "Cashu service test")
        assertTrue("createPayment should succeed", result is WalletResult.Success)
        val data = result.getOrThrow()
        assertNotNull("PaymentData should have cashuPR", data.cashuPR)
        assertTrue("cashuPR should start with creqA", data.cashuPR!!.startsWith("creqA"))
        println("Service cashuPR: ${data.cashuPR.take(50)}...")
    }

    @Test
    fun testCashuFetchExistingPaymentData_returnsCashuPR() = runBlocking {
        skipIfCashuNotEnabled()
        val invoiceId = createInvoiceId(500L)
        val result = service.fetchExistingPaymentData(invoiceId)
        assertTrue("fetchExistingPaymentData should succeed", result is WalletResult.Success)
        val data = result.getOrThrow()
        assertEquals("Should return same invoice ID", invoiceId, data.paymentId)
        assertNotNull("Should include cashuPR", data.cashuPR)
    }

    // =========================================================================
    // Lightning e2e
    // =========================================================================

    /**
     * Full e2e: create BTCPay Lightning invoice → pay bolt11 via customer_lnd
     * → poll until invoice is PAID.
     *
     * Requires the channel customer_lnd → lnd_bitcoin set up by channel-setup.sh.
     */
    @Test
    fun testE2E_lightningInvoicePaidViaCustomerLnd() = runBlocking {
        if (skipIfNotConfigured()) return@runBlocking
        assumeFalse(
            "customer_lnd not reachable at $customerLndUrl — skipping e2e test",
            !isCustomerLndReachable()
        )

        // 1. Create BTCPay invoice and fetch bolt11
        val data = service.createPayment(1000L, "LN e2e test").getOrThrow()
        val bolt11 = data.bolt11
        assertNotNull("Invoice should have a bolt11", bolt11)
        println("BTCPay invoice: ${data.paymentId}")
        println("Paying: ${bolt11!!.take(60)}...")

        // 2. Pay via customer_lnd
        val payReq = Request.Builder()
            .url("$customerLndUrl/v1/channels/transactions")
            .post("""{"payment_request": "$bolt11"}""".toRequestBody(jsonMediaType))
            .build()
        val (payCode, payResp) = executeHttpRequest(payReq)
        println("customer_lnd pay (HTTP $payCode): ${payResp.take(200)}")
        assertTrue("customer_lnd should accept payment (HTTP $payCode)", payCode in 200..299)

        // 3. Poll BTCPay until invoice is PAID (up to 30 s)
        val isPaid = pollBtcPayInvoicePaid(data.paymentId, timeoutMs = 30_000)
        assertTrue("BTCPay invoice should become PAID after LN payment", isPaid)
        println("BTCPay invoice PAID — Lightning e2e passed!")
    }

    private suspend fun pollBtcPayInvoicePaid(invoiceId: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val status = service.checkPaymentStatus(invoiceId).getOrNull()
            println("  BTCPay invoice status: $status")
            if (status == PaymentState.PAID) return true
            kotlinx.coroutines.delay(2_000)
        }
        return false
    }

    // =========================================================================
    // CDK Mint + BTCPay — combined e2e
    // =========================================================================

    private fun isCdkMintReachable(): Boolean = try {
        val req = Request.Builder().url("$cdkMintUrl/v1/info").get().build()
        httpClient.newCall(req).execute().use { it.code == 200 }
    } catch (_: Exception) { false }

    private fun skipIfCdkMintNotReachable() {
        assumeFalse("CDK mint not reachable at $cdkMintUrl — skipping", !isCdkMintReachable())
    }

    /**
     * Verifies that BTCPay's Cashu config lists the local CDK mint as a trusted mint.
     * This is set up by provision.sh using the Docker-internal URL (http://cdk-mint:3338).
     * BTCPay resolves it internally; the display URL may differ from cdkMintUrl.
     */
    @Test
    fun testBtcPayTrustsCdkMint() {
        skipIfCashuNotEnabled()
        skipIfCdkMintNotReachable()
        val (code, body) = cashuGet("")
        assertEquals("GET /cashu should return 200", 200, code)
        val trustedMints = JsonParser.parseString(body).asJsonObject
            .getAsJsonArray("trustedMintsUrls")
        val hasCdkMint = trustedMints.any { url ->
            // provision.sh sets "http://cdk-mint:3338" (Docker hostname)
            url.asString.contains("cdk-mint") || url.asString.contains("3338")
        }
        assertTrue(
            "BTCPay should trust the CDK mint (trustedMintsUrls=$trustedMints)",
            hasCdkMint
        )
        println("BTCPay trusted mints: $trustedMints")
    }

    /**
     * Creates a BTCPay invoice, creates a CDK mint quote for the same amount,
     * pays the CDK mint invoice via customer_lnd, and verifies the CDK quote
     * becomes PAID — confirming the Lightning topology works end-to-end.
     *
     * Note: completing the payment to BTCPay (redeeming the minted token via
     * NUT-18/19) requires Cashu token blinding which is not available in JVM
     * tests without CDK native libs. This test validates the infrastructure layer.
     */
    @Test
    fun testE2E_cdkMintQuotePaidViaCustomerLnd() = runBlocking {
        skipIfCashuNotEnabled()
        skipIfCdkMintNotReachable()
        assumeFalse(
            "customer_lnd not reachable at $customerLndUrl — skipping e2e test",
            !isCustomerLndReachable()
        )

        val amountSats = 100L

        // Create a BTCPay invoice (proves BTCPay + CDK mint are wired together)
        val btcpayInvoiceId = createInvoiceId(amountSats, "CDK e2e test")
        assertFalse("BTCPay invoice ID should not be blank", btcpayInvoiceId.isBlank())
        println("BTCPay invoice: $btcpayInvoiceId")

        // Create a CDK mint quote (get bolt11 from CDK mint)
        val mintQuoteReq = Request.Builder()
            .url("$cdkMintUrl/v1/mint/quote/bolt11")
            .post("""{"amount": $amountSats, "unit": "sat"}""".toRequestBody(jsonMediaType))
            .build()
        val (quoteCode, quoteBody) = httpClient.newCall(mintQuoteReq).execute().use {
            it.code to (it.body?.string() ?: "")
        }
        assertEquals("CDK mint quote should succeed", 200, quoteCode)
        val quoteJson = JsonParser.parseString(quoteBody).asJsonObject
        val quoteId = quoteJson.get("quote").asString
        val bolt11 = quoteJson.get("request").asString
        println("CDK mint quote: $quoteId")
        println("Paying: ${bolt11.take(60)}...")

        // Pay via customer_lnd
        val payReq = Request.Builder()
            .url("$customerLndUrl/v1/channels/transactions")
            .post("""{"payment_request": "$bolt11"}""".toRequestBody(jsonMediaType))
            .build()
        val (payCode, payResp) = httpClient.newCall(payReq).execute().use {
            it.code to (it.body?.string() ?: "")
        }
        println("LND pay (HTTP $payCode): ${payResp.take(200)}")
        assertTrue("customer_lnd should accept payment (HTTP $payCode)", payCode in 200..299)

        // Poll CDK mint until quote is PAID (up to 30 s)
        val isPaid = pollCdkQuotePaid(quoteId, timeoutMs = 30_000)
        assertTrue("CDK mint quote should be PAID after LN payment", isPaid)
        println("CDK mint quote PAID — e2e infrastructure test passed!")
    }

    private fun isCustomerLndReachable(): Boolean = try {
        val req = Request.Builder().url("$customerLndUrl/v1/getinfo").get().build()
        httpClient.newCall(req).execute().use { it.code == 200 }
    } catch (_: Exception) { false }

    private suspend fun pollCdkQuotePaid(quoteId: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                val req = Request.Builder()
                    .url("$cdkMintUrl/v1/mint/quote/bolt11/$quoteId")
                    .get().build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.code == 200) {
                        val state = JsonParser.parseString(resp.body?.string() ?: "{}")
                            .asJsonObject.get("state")?.asString
                        println("  CDK quote state: $state")
                        if (state == "PAID") return true
                    }
                }
            } catch (_: Exception) { /* retry */ }
            kotlinx.coroutines.delay(2_000)
        }
        return false
    }
}
