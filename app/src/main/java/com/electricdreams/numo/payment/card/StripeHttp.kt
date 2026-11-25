package com.electricdreams.numo.payment.card

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/** Shared plumbing for the few Stripe REST calls Numo makes with the merchant's restricted key. */
internal object StripeHttp {

    val defaultClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Runs [request] and returns Stripe's JSON object.
     *
     * @throws StripeConnectionTokenClient.StripeApiException when Stripe rejects the request.
     * @throws IOException on network failure or a response that is not a JSON object.
     */
    fun execute(client: OkHttpClient, request: Request): JsonObject {
        client.newCall(request).execute().use { response ->
            val json = parseObject(response.body?.string().orEmpty())
            if (!response.isSuccessful) {
                val error = json?.getAsJsonObject("error")
                throw StripeConnectionTokenClient.StripeApiException(
                    message = error.string("message") ?: "Stripe returned HTTP ${response.code}",
                    httpStatus = response.code,
                    stripeCode = error.string("code"),
                    param = error.string("param"),
                )
            }
            return json ?: throw IOException("Stripe returned an unexpected response")
        }
    }

    fun JsonObject?.string(name: String): String? =
        this?.get(name)?.takeIf { it.isJsonPrimitive }?.asString

    private fun parseObject(text: String): JsonObject? = try {
        JsonParser.parseString(text).takeIf { it.isJsonObject }?.asJsonObject
    } catch (e: Exception) {
        null
    }
}
