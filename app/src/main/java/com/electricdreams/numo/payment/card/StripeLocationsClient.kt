package com.electricdreams.numo.payment.card

import com.electricdreams.numo.payment.card.StripeHttp.string
import com.google.gson.JsonObject
import java.io.IOException
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** A Stripe Terminal Location: the place a Tap to Pay reader is registered to. */
data class StripeLocation(
    val id: String,
    val displayName: String,
    val city: String?,
    val country: String?,
) {
    /** "Washington, US", or whichever part Stripe has. */
    val place: String get() = listOfNotNull(city, country).filter { it.isNotBlank() }.joinToString(", ")
}

/** Address of a new location. Stripe requires the country and, depending on it, other fields. */
data class StripeLocationAddress(
    val line1: String,
    val line2: String?,
    val city: String,
    val state: String?,
    val postalCode: String,
    /** ISO 3166-1 alpha-2 code, e.g. "US". */
    val country: String,
)

/**
 * Lists and creates Terminal Locations with the merchant's restricted key, so setup never asks
 * for a location ID. The key needs the "Terminal Locations: Write" permission for this; taking
 * payments does not.
 */
class StripeLocationsClient(
    private val baseUrl: HttpUrl = StripeConnectionTokenClient.DEFAULT_BASE_URL.toHttpUrl(),
    private val httpClient: OkHttpClient = StripeHttp.defaultClient,
) {

    /**
     * @throws StripeConnectionTokenClient.StripeApiException when Stripe rejects the request.
     * @throws IOException on network failure or an unexpected response.
     */
    fun listLocations(restrictedKey: String): List<StripeLocation> {
        val url = baseUrl.newBuilder()
            .addPathSegments("v1/terminal/locations")
            .addQueryParameter("limit", "100")
        val locations = mutableListOf<StripeLocation>()
        val cursors = mutableSetOf<String>()
        while (true) {
            val request = authorized(restrictedKey).url(url.build()).get().build()
            val json = StripeHttp.execute(httpClient, request)
            val data = json.get("data")?.takeIf { it.isJsonArray }?.asJsonArray
                ?: throw IOException("Stripe response did not include locations")
            data.mapNotNullTo(locations) { element ->
                element.takeIf { it.isJsonObject }?.asJsonObject?.let(::parseLocation)
            }
            if (json.get("has_more")?.asBoolean != true) return locations
            val cursor = data.lastOrNull()?.takeIf { it.isJsonObject }?.asJsonObject.string("id")
            if (cursor.isNullOrBlank() || !cursors.add(cursor)) {
                throw IOException("Stripe did not return a new location cursor")
            }
            url.setQueryParameter("starting_after", cursor)
        }
    }

    /**
     * Looks up one location, for a setup where only its ID was entered.
     *
     * @throws IOException as [listLocations].
     */
    fun getLocation(restrictedKey: String, locationId: String): StripeLocation {
        val url = baseUrl.newBuilder()
            .addPathSegments("v1/terminal/locations")
            .addPathSegment(locationId.trim())
            .build()
        val json = StripeHttp.execute(httpClient, authorized(restrictedKey).url(url).get().build())
        return parseLocation(json) ?: throw IOException("Stripe response did not include the location")
    }

    /** @throws IOException as [listLocations]. */
    fun createLocation(
        restrictedKey: String,
        displayName: String,
        address: StripeLocationAddress,
        idempotencyKey: String,
    ): StripeLocation {
        val url = baseUrl.newBuilder().addPathSegments("v1/terminal/locations").build()
        val body = FormBody.Builder()
            .add("display_name", displayName.trim())
            .add("address[line1]", address.line1.trim())
            .apply {
                address.city.trim().takeIf { it.isNotEmpty() }?.let { add("address[city]", it) }
                address.postalCode.trim().takeIf { it.isNotEmpty() }?.let { add("address[postal_code]", it) }
                add("address[country]", address.country.trim().uppercase())
                address.line2?.trim()?.takeIf { it.isNotEmpty() }?.let { add("address[line2]", it) }
                address.state?.trim()?.takeIf { it.isNotEmpty() }?.let { add("address[state]", it) }
            }
            .build()
        val request = authorized(restrictedKey).url(url).post(body)
            .header("Idempotency-Key", idempotencyKey).build()
        val json = StripeHttp.execute(httpClient, request)
        return parseLocation(json) ?: throw IOException("Stripe response did not include the location")
    }

    private fun authorized(restrictedKey: String) =
        Request.Builder().header("Authorization", "Bearer ${restrictedKey.trim()}")

    private fun parseLocation(json: JsonObject): StripeLocation? {
        val id = json.string("id")?.takeIf(StripeCardConfig::isValidLocationId) ?: return null
        val address = json.get("address")?.takeIf { it.isJsonObject }?.asJsonObject
        return StripeLocation(
            id = id,
            displayName = json.string("display_name")?.takeIf { it.isNotBlank() } ?: id,
            city = address.string("city"),
            country = address.string("country"),
        )
    }
}
