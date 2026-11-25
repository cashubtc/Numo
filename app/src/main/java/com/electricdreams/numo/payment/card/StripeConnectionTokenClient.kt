package com.electricdreams.numo.payment.card

import java.io.IOException
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Requests Stripe Terminal connection tokens directly from Stripe with the merchant's
 * restricted key. The key needs only the "Terminal Connection Tokens: Write" permission.
 */
class StripeConnectionTokenClient(
    private val baseUrl: HttpUrl = DEFAULT_BASE_URL.toHttpUrl(),
    private val httpClient: OkHttpClient = defaultHttpClient,
) {

    /** Thrown with Stripe's own error message so the merchant can fix the key or location. */
    class StripeApiException(
        message: String,
        val httpStatus: Int,
        val stripeCode: String?,
        /** The request parameter Stripe blames, e.g. "location" for an unknown location. */
        val param: String? = null,
    ) : IOException(message)

    /**
     * Creates a single-use connection token. Never cache the result: Stripe requires a
     * fresh token each time the SDK asks for one.
     *
     * @throws StripeApiException when Stripe rejects the request.
     * @throws IOException on network failure or an unexpected response.
     */
    fun createConnectionToken(config: StripeCardConfig): String {
        val url = baseUrl.newBuilder()
            .addPathSegments("v1/terminal/connection_tokens")
            .build()
        val body = FormBody.Builder()
            .add("location", config.locationId)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.restrictedKey}")
            .post(body)
            .build()

        val json = StripeHttp.execute(httpClient, request)
        val secret = json.get("secret")?.takeIf { it.isJsonPrimitive }?.asString
        if (secret.isNullOrBlank()) {
            throw IOException("Stripe response did not include a connection token")
        }
        return secret
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.stripe.com/"

        private val defaultHttpClient: OkHttpClient get() = StripeHttp.defaultClient
    }
}
