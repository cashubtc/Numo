package com.electricdreams.numo.payment

import android.content.Context
import android.util.Log
import com.electricdreams.numo.BuildConfig
import com.electricdreams.numo.core.data.model.PaymentHistoryEntry
import com.electricdreams.numo.core.model.CheckoutBasket
import com.electricdreams.numo.core.model.CheckoutBasketItem
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
    private val endpointProvider: () -> List<WebhookSettingsManager.WebhookEndpointConfig> = {
        WebhookSettingsManager.getInstance(context.applicationContext).getEndpoints()
    },
    private val httpClient: OkHttpClient = defaultHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val retryDelaysMs: List<Long> = listOf(0L, 1_000L, 2_500L),
) {
    data class PaymentSummary(
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

    data class SwapToLightningMintMetadata(
        val unknownMintUrl: String,
        val meltQuoteId: String,
        val lightningMintUrl: String,
        val lightningQuoteId: String,
    )

    data class TransactionMetadata(
        val paymentId: String,
        val status: String,
        val paymentType: String,
        val amountSats: Long,
        val baseAmountSats: Long,
        val tipAmountSats: Long,
        val tipPercentage: Int,
        val unit: String,
        val entryUnit: String,
        val enteredAmount: Long,
        val formattedAmount: String?,
        val bitcoinPrice: Double?,
        val mintUrl: String?,
        val lightningInvoice: String?,
        val lightningQuoteId: String?,
        val lightningMintUrl: String?,
        val paymentRequest: String?,
        val basketId: String?,
        val dateMs: Long,
        val swapToLightningMint: SwapToLightningMintMetadata?,
    )

    data class CheckoutLineItem(
        val itemId: String,
        val uuid: String,
        val name: String,
        val variationName: String?,
        val sku: String?,
        val category: String?,
        val quantity: Int,
        val priceType: String,
        val netPriceCents: Long,
        val priceSats: Long,
        val priceCurrency: String,
        val vatEnabled: Boolean,
        val vatRate: Int,
        val displayName: String,
        val netTotalCents: Long,
        val netTotalSats: Long,
        val vatPerUnitCents: Long,
        val totalVatCents: Long,
        val grossPricePerUnitCents: Long,
        val grossTotalCents: Long,
    )

    data class CheckoutMetadata(
        val checkoutBasketId: String,
        val savedBasketId: String?,
        val checkoutTimestamp: Long,
        val currency: String,
        val bitcoinPrice: Double?,
        val totalSatoshis: Long,
        val itemCount: Int,
        val hasVat: Boolean,
        val hasMixedPriceTypes: Boolean,
        val fiatNetTotalCents: Long,
        val fiatVatTotalCents: Long,
        val fiatGrossTotalCents: Long,
        val satsDirectTotal: Long,
        val vatBreakdown: Map<Int, Long>,
        val items: List<CheckoutLineItem>,
    )

    data class PaymentReceivedEvent(
        val payment: PaymentSummary,
        val transaction: TransactionMetadata?,
        val checkout: CheckoutMetadata?,
    )

    data class DispatchResult(
        val totalEndpoints: Int,
        val successCount: Int,
        val failureCount: Int,
    )

    data class WebhookPayload(
        val event: String,
        val payloadVersion: Int,
        val eventId: String,
        val timestampMs: Long,
        val timestampIso: String,
        val payment: PaymentSummary,
        val transaction: TransactionMetadata?,
        val checkout: CheckoutMetadata?,
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

    fun dispatchPaymentReceived(entry: PaymentHistoryEntry) {
        dispatchPaymentReceived(toPaymentReceivedEvent(entry))
    }

    fun dispatchPaymentReceived(event: PaymentReceivedEvent) {
        scope.launch {
            dispatchPaymentReceivedNow(event)
        }
    }

    suspend fun dispatchPaymentReceivedNow(entry: PaymentHistoryEntry): DispatchResult =
        dispatchPaymentReceivedNow(toPaymentReceivedEvent(entry))

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
                payloadVersion = PAYLOAD_VERSION,
                eventId = eventId,
                timestampMs = now,
                timestampIso = formatIsoTimestamp(now),
                payment = event.payment,
                transaction = event.transaction,
                checkout = event.checkout,
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

    private suspend fun postWithRetry(
        endpoint: WebhookSettingsManager.WebhookEndpointConfig,
        jsonBody: String,
        eventId: String,
    ): Boolean {
        retryDelaysMs.forEachIndexed { attemptIndex, delayMs ->
            if (delayMs > 0) {
                delay(delayMs)
            }

            try {
                val requestBody = jsonBody.toRequestBody(JSON_MEDIA_TYPE)
                val requestBuilder = Request.Builder()
                    .url(endpoint.url)
                    .post(requestBody)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("User-Agent", "Numo/${BuildConfig.VERSION_NAME}")
                    .header("X-Numo-Event", EVENT_PAYMENT_RECEIVED)
                    .header("X-Numo-Event-Id", eventId)

                endpoint.authKey?.takeIf { it.isNotBlank() }?.let { authKey ->
                    requestBuilder.header("Authorization", authKey)
                }

                val request = requestBuilder.build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        return true
                    }

                    Log.w(
                        TAG,
                        "Webhook POST failed url=${endpoint.url} status=${response.code} attempt=${attemptIndex + 1}/${retryDelaysMs.size}",
                    )
                }
            } catch (e: Exception) {
                Log.w(
                    TAG,
                    "Webhook POST error url=${endpoint.url} attempt=${attemptIndex + 1}/${retryDelaysMs.size}: ${e.message}",
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

    private fun toPaymentReceivedEvent(entry: PaymentHistoryEntry): PaymentReceivedEvent {
        val paymentType = entry.paymentType ?: inferPaymentType(entry)
        val mintUrl = entry.mintUrl ?: entry.lightningMintUrl

        return PaymentReceivedEvent(
            payment = PaymentSummary(
                paymentId = entry.id,
                amountSats = entry.amount,
                paymentType = paymentType,
                status = entry.getStatus(),
                mintUrl = mintUrl,
                tipAmountSats = entry.tipAmountSats,
                tipPercentage = entry.tipPercentage,
                basketId = entry.basketId,
                lightningInvoice = entry.lightningInvoice,
                lightningQuoteId = entry.lightningQuoteId,
                lightningMintUrl = entry.lightningMintUrl,
            ),
            transaction = TransactionMetadata(
                paymentId = entry.id,
                status = entry.getStatus(),
                paymentType = paymentType,
                amountSats = entry.amount,
                baseAmountSats = entry.getBaseAmountSats(),
                tipAmountSats = entry.tipAmountSats,
                tipPercentage = entry.tipPercentage,
                unit = entry.getUnit(),
                entryUnit = entry.getEntryUnit(),
                enteredAmount = entry.enteredAmount,
                formattedAmount = entry.formattedAmount,
                bitcoinPrice = entry.bitcoinPrice,
                mintUrl = mintUrl,
                lightningInvoice = entry.lightningInvoice,
                lightningQuoteId = entry.lightningQuoteId,
                lightningMintUrl = entry.lightningMintUrl,
                paymentRequest = entry.paymentRequest,
                basketId = entry.basketId,
                dateMs = entry.date.time,
                swapToLightningMint = parseSwapToLightningMint(entry.swapToLightningMintJson),
            ),
            checkout = entry.getCheckoutBasket()?.let { basket ->
                toCheckoutMetadata(
                    basket = basket,
                    savedBasketId = entry.basketId,
                )
            },
        )
    }

    private fun inferPaymentType(entry: PaymentHistoryEntry): String {
        return if (!entry.lightningInvoice.isNullOrBlank() || !entry.lightningQuoteId.isNullOrBlank()) {
            PaymentHistoryEntry.TYPE_LIGHTNING
        } else {
            PaymentHistoryEntry.TYPE_CASHU
        }
    }

    private fun toCheckoutMetadata(
        basket: CheckoutBasket,
        savedBasketId: String?,
    ): CheckoutMetadata {
        return CheckoutMetadata(
            checkoutBasketId = basket.id,
            savedBasketId = savedBasketId,
            checkoutTimestamp = basket.checkoutTimestamp,
            currency = basket.currency,
            bitcoinPrice = basket.bitcoinPrice,
            totalSatoshis = basket.totalSatoshis,
            itemCount = basket.getTotalItemCount(),
            hasVat = basket.hasVat(),
            hasMixedPriceTypes = basket.hasMixedPriceTypes(),
            fiatNetTotalCents = basket.getFiatNetTotalCents(),
            fiatVatTotalCents = basket.getFiatVatTotalCents(),
            fiatGrossTotalCents = basket.getFiatGrossTotalCents(),
            satsDirectTotal = basket.getSatsDirectTotal(),
            vatBreakdown = basket.getVatBreakdown(),
            items = basket.items.map(::toCheckoutLineItem),
        )
    }

    private fun toCheckoutLineItem(item: CheckoutBasketItem): CheckoutLineItem {
        return CheckoutLineItem(
            itemId = item.itemId,
            uuid = item.uuid,
            name = item.name,
            variationName = item.variationName,
            sku = item.sku,
            category = item.category,
            quantity = item.quantity,
            priceType = item.priceType,
            netPriceCents = item.netPriceCents,
            priceSats = item.priceSats,
            priceCurrency = item.priceCurrency,
            vatEnabled = item.vatEnabled,
            vatRate = item.vatRate,
            displayName = item.displayName,
            netTotalCents = item.getNetTotalCents(),
            netTotalSats = item.getNetTotalSats(),
            vatPerUnitCents = item.getVatPerUnitCents(),
            totalVatCents = item.getTotalVatCents(),
            grossPricePerUnitCents = item.getGrossPricePerUnitCents(),
            grossTotalCents = item.getGrossTotalCents(),
        )
    }

    private fun parseSwapToLightningMint(json: String?): SwapToLightningMintMetadata? {
        if (json.isNullOrBlank()) {
            return null
        }

        return try {
            val frame = gson.fromJson(
                json,
                PaymentHistoryEntry.Companion.SwapToLightningMintFrame::class.java,
            )

            if (frame == null) {
                null
            } else {
                SwapToLightningMintMetadata(
                    unknownMintUrl = frame.unknownMintUrl,
                    meltQuoteId = frame.meltQuoteId,
                    lightningMintUrl = frame.lightningMintUrl,
                    lightningQuoteId = frame.lightningQuoteId,
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "PaymentWebhookDispatch"
        private const val EVENT_PAYMENT_RECEIVED = "payment.received"
        private const val PAYLOAD_VERSION = 2
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
