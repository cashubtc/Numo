package com.electricdreams.numo.core.payment.impl

import android.util.Log
import com.electricdreams.numo.core.payment.BtcPayConfig
import com.electricdreams.numo.core.payment.PaymentData
import com.electricdreams.numo.core.payment.PaymentService
import com.electricdreams.numo.core.payment.PaymentState
import com.electricdreams.numo.core.payment.RedeemResult
import com.electricdreams.numo.core.wallet.Satoshis
import com.electricdreams.numo.core.wallet.WalletError
import com.electricdreams.numo.core.wallet.WalletResult
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * [PaymentService] backed by a BTCPay Server (Greenfield API) + BTCNutServer.
 *
 * Flow:
 * 1. `createPayment()` creates an invoice via BTCPay, then fetches payment methods
 *    to obtain the BOLT11 invoice and Cashu payment request (`creq…`).
 * 2. `checkPaymentStatus()` polls the invoice status.
 * 3. `redeemToken()` posts the Cashu token to BTCNutServer to settle the invoice.
 */
class BtcPayPaymentService(
    private val config: BtcPayConfig
) : PaymentService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // -------------------------------------------------------------------
    // PaymentService
    // -------------------------------------------------------------------

    override suspend fun createPayment(
        amountSats: Long,
        description: String?
    ): WalletResult<PaymentData> = withContext(Dispatchers.IO) {
        WalletResult.runCatching {
            // 1. Create invoice
            val invoiceId = createInvoice(amountSats, description)

            // 2. Fetch payment methods to get bolt11 + cashu PR.
            //    BTCPay may return null destinations on the first call while
            //    it generates the lightning invoice, so retry a few times.
            var bolt11: String? = null
            var cashuPR: String? = null

            for (attempt in 1..5) {
                val (b, c) = fetchPaymentMethods(invoiceId)
                if (b != null) bolt11 = b
                if (c != null) cashuPR = c
                if (bolt11 != null && cashuPR != null) break
                Log.d(TAG, "Payment methods attempt $attempt: bolt11=${bolt11 != null}, cashuPR=${cashuPR != null}")
                delay(1000)
            }

            PaymentData(
                paymentId = invoiceId,
                bolt11 = bolt11,
                cashuPR = cashuPR,
                mintUrl = null,
                expiresAt = null
            )
        }
    }

    override suspend fun checkPaymentStatus(paymentId: String): WalletResult<PaymentState> =
        withContext(Dispatchers.IO) {
            WalletResult.runCatching {
                val url = "${baseUrl()}/api/v1/stores/${config.storeId}/invoices/$paymentId"
                val request = authorizedGet(url)
                val body = executeForBody(request)
                val json = JsonParser.parseString(body).asJsonObject
                val status = json.get("status")?.asString ?: "Invalid"
                mapInvoiceStatus(status)
            }
        }

    override suspend fun redeemToken(
        token: String,
        paymentId: String?
    ): WalletResult<RedeemResult> = withContext(Dispatchers.IO) {
        WalletResult.runCatching {
            val urlBuilder = StringBuilder("${baseUrl()}/cashu/pay-invoice?token=$token")
            if (!paymentId.isNullOrBlank()) {
                urlBuilder.append("&invoiceId=$paymentId")
            }
            val request = Request.Builder()
                .url(urlBuilder.toString())
                .post("".toRequestBody(jsonMediaType))
                .addHeader("Authorization", "token ${config.apiKey}")
                .build()

            executeForBody(request)

            // BTCNutServer does not return detailed amount info; return a
            // placeholder so the caller knows the operation succeeded.
            RedeemResult(amount = Satoshis(0), proofsCount = 0)
        }
    }

    override fun isReady(): Boolean {
        return config.serverUrl.isNotBlank()
                && config.apiKey.isNotBlank()
                && config.storeId.isNotBlank()
    }

    // -------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------

    private fun baseUrl(): String = config.serverUrl.trimEnd('/')

    private fun authorizedGet(url: String): Request = Request.Builder()
        .url(url)
        .get()
        .addHeader("Authorization", "token ${config.apiKey}")
        .build()

    /**
     * Create a BTCPay invoice and return the invoice ID.
     */
    private fun createInvoice(amountSats: Long, description: String?): String {
        val payload = JsonObject().apply {
            // BTCPay expects the amount as a string in the currency unit.
            // For BTC-denominated stores this is BTC; for sats-denominated stores
            // it is sats. We pass sats and rely on the store being configured for
            // the "SATS" denomination.
            addProperty("amount", amountSats.toString())
            addProperty("currency", "SATS")
            if (!description.isNullOrBlank()) {
                val metadata = JsonObject()
                metadata.addProperty("itemDesc", description)
                add("metadata", metadata)
            }
        }

        val request = Request.Builder()
            .url("${baseUrl()}/api/v1/stores/${config.storeId}/invoices")
            .post(gson.toJson(payload).toRequestBody(jsonMediaType))
            .addHeader("Authorization", "token ${config.apiKey}")
            .build()

        val body = executeForBody(request)
        val json = JsonParser.parseString(body).asJsonObject
        return json.get("id")?.asString
            ?: throw WalletError.Unknown("BTCPay invoice response missing 'id'")
    }

    /**
     * Fetch payment methods for an invoice.
     * Returns (bolt11, cashuPR) – either may be null if the method is not available.
     */
    private fun fetchPaymentMethods(invoiceId: String): Pair<String?, String?> {
        val url = "${baseUrl()}/api/v1/stores/${config.storeId}/invoices/$invoiceId/payment-methods"
        val request = authorizedGet(url)
        val body = executeForBody(request)
        Log.d(TAG, "Payment methods response: $body")
        val array = JsonParser.parseString(body).asJsonArray

        var bolt11: String? = null
        var cashuPR: String? = null

        for (element in array) {
            val obj = element.asJsonObject
            val paymentMethod = obj.get("paymentMethodId")?.takeIf { !it.isJsonNull }?.asString
                ?: obj.get("paymentMethod")?.takeIf { !it.isJsonNull }?.asString
                ?: ""
            val destination = obj.get("destination")?.takeIf { !it.isJsonNull }?.asString

            Log.d(TAG, "Payment method: '$paymentMethod', destination: ${if (destination != null) "'${destination.take(30)}...'" else "null"}")

            when {
                paymentMethod.equals("BTC-LN", ignoreCase = true)
                        || paymentMethod.contains("LightningNetwork", ignoreCase = true) -> {
                    if (destination != null) bolt11 = destination
                }
                paymentMethod.contains("Cashu", ignoreCase = true) -> {
                    if (destination != null) cashuPR = destination
                }
            }
        }

        Log.d(TAG, "Resolved bolt11=${bolt11 != null}, cashuPR=${cashuPR != null}")
        return Pair(bolt11, cashuPR)
    }

    private fun executeForBody(request: Request): String {
        val response = client.newCall(request).execute()
        val body = response.body?.string()
        if (!response.isSuccessful) {
            Log.e(TAG, "BTCPay request failed: ${response.code} ${response.message} body=$body")
            throw WalletError.NetworkError(
                "BTCPay request failed (${response.code}): ${body?.take(200) ?: response.message}"
            )
        }
        return body ?: throw WalletError.NetworkError("Empty response body from BTCPay")
    }

    private fun mapInvoiceStatus(status: String): PaymentState = when (status) {
        "New" -> PaymentState.PENDING
        "Processing" -> PaymentState.PENDING
        "Settled" -> PaymentState.PAID
        "Expired" -> PaymentState.EXPIRED
        "Invalid" -> PaymentState.FAILED
        else -> {
            Log.w(TAG, "Unknown BTCPay invoice status: $status")
            PaymentState.FAILED
        }
    }

    companion object {
        private const val TAG = "BtcPayPaymentService"
    }
}
