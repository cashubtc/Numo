package com.electricdreams.numo.payment

import android.content.Context
import android.util.Log
import com.electricdreams.numo.BuildConfig
import com.electricdreams.numo.core.util.WebhookSettingsManager
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Sends best-effort payment webhooks to configured endpoints.
 */
class PaymentWebhookDispatcher(
    context: Context,
    private val endpointProvider: () -> List<String> = {
        WebhookSettingsManager.getInstance(context.applicationContext).getEndpoints()
    },
    private val httpClient: OkHttpClient = defaultHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val retryDelaysMs: List<Long> = listOf(0L, 1_000L, 2_500L),
) {
    data class PaymentReceivedEvent(
        val paymentId: String?,
        val amountSats: Long,
        val paymentType: String,
        val status: String,
        val mintUrl: String?,
        val tipAmountSats: Long,
        val tipPercentage: Int,
        val basketId: String?,
        val lightningInvoice: String?,
        val lightningQuoteId: String?,
        val lightningMintUrl: String?,
    )

    data class DispatchResult(
        val totalEndpoints: Int,
        val successCount: Int,
        val failureCount: Int,
    )

    data class WebhookPayload(
        val event: String,
        val eventId: String,
        val timestampMs: Long,
        val timestampIso: String,
        val payment: PaymentReceivedEvent,
        val terminal: TerminalMeta,
    )

    data class TerminalMeta(
        val platform: String,
        val appPackage: String,
        val appVersionName: String,
        val appVersionCode: Int,
    )

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val gson = Gson()

    fun dispatchPaymentReceived(event: PaymentReceivedEvent) {
        scope.launch {
            dispatchPaymentReceivedNow(event)
        }
    }

    suspend fun dispatchPaymentReceivedNow(event: PaymentReceivedEvent): DispatchResult =
        withContext(ioDispatcher) {
            val endpoints = endpointProvider.invoke()
            if (endpoints.isEmpty()) {
                return@withContext DispatchResult(0, 0, 0)
            }

            val now = System.currentTimeMillis()
            val eventId = UUID.randomUUID().toString()
            val payload = WebhookPayload(
                event = EVENT_PAYMENT_RECEIVED,
                eventId = eventId,
                timestampMs = now,
                timestampIso = formatIsoTimestamp(now),
                payment = event,
                terminal = TerminalMeta(
                    platform = "android",
                    appPackage = appContext.packageName,
                    appVersionName = BuildConfig.VERSION_NAME,
                    appVersionCode = BuildConfig.VERSION_CODE,
                ),
            )

            val payloadJson = gson.toJson(payload)
            var successCount = 0

            endpoints.forEach { endpoint ->
                if (postWithRetry(endpoint, payloadJson, eventId)) {
                    successCount += 1
                }
            }

            DispatchResult(
                totalEndpoints = endpoints.size,
                successCount = successCount,
                failureCount = endpoints.size - successCount,
            )
        }

    private suspend fun postWithRetry(url: String, jsonBody: String, eventId: String): Boolean {
        retryDelaysMs.forEachIndexed { attemptIndex, delayMs ->
            if (delayMs > 0) {
                delay(delayMs)
            }

            try {
                val requestBody = jsonBody.toRequestBody(JSON_MEDIA_TYPE)
                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("User-Agent", "Numo/${BuildConfig.VERSION_NAME}")
                    .header("X-Numo-Event", EVENT_PAYMENT_RECEIVED)
                    .header("X-Numo-Event-Id", eventId)
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        return true
                    }

                    Log.w(
                        TAG,
                        "Webhook POST failed url=$url status=${response.code} attempt=${attemptIndex + 1}/${retryDelaysMs.size}",
                    )
                }
            } catch (e: Exception) {
                Log.w(
                    TAG,
                    "Webhook POST error url=$url attempt=${attemptIndex + 1}/${retryDelaysMs.size}: ${e.message}",
                )
            }
        }

        return false
    }

    private fun formatIsoTimestamp(timestampMs: Long): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(Date(timestampMs))
    }

    companion object {
        private const val TAG = "PaymentWebhookDispatch"
        private const val EVENT_PAYMENT_RECEIVED = "payment.received"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private val defaultHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build()
        }

        @Volatile
        private var instance: PaymentWebhookDispatcher? = null

        fun getInstance(context: Context): PaymentWebhookDispatcher {
            return instance ?: synchronized(this) {
                instance ?: PaymentWebhookDispatcher(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
}
