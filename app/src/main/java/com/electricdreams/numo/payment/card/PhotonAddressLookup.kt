package com.electricdreams.numo.payment.card

import com.electricdreams.numo.BuildConfig
import com.electricdreams.numo.payment.card.StripeHttp.string
import java.util.Locale
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

internal data class AddressQuery(
    val street: String,
    val postalCode: String,
    val city: String,
    val countryCode: String?,
)

internal data class AddressSuggestion(
    val line1: String,
    val postalCode: String?,
    val city: String?,
    val state: String?,
    val countryCode: String?,
) {
    override fun toString(): String = listOfNotNull(
        line1,
        listOfNotNull(postalCode, city).joinToString(" ").ifEmpty { null },
    ).joinToString(", ")
}

internal fun interface AddressLookup {
    fun search(query: AddressQuery): List<AddressSuggestion>
}

internal class PhotonAddressLookup(
    private val baseUrl: HttpUrl = DEFAULT_BASE_URL.toHttpUrl(),
    private val httpClient: OkHttpClient = StripeHttp.defaultClient,
) : AddressLookup {
    override fun search(query: AddressQuery): List<AddressSuggestion> {
        val url = baseUrl.newBuilder()
            .addPathSegment("structured")
            .addQueryParameter("street", query.street)
            .addQueryParameter("limit", "5")
            .apply {
                query.postalCode.takeIf { it.isNotBlank() }
                    ?.let { addQueryParameter("postcode", it) }
                query.city.takeIf { it.isNotBlank() }?.let { addQueryParameter("city", it) }
                query.countryCode?.takeIf { it.isNotBlank() }
                    ?.let { addQueryParameter("countrycode", it) }
            }
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Numo/${BuildConfig.VERSION_NAME} (Android)")
            .header("Accept-Language", Locale.getDefault().language)
            .get()
            .build()
        val json = StripeHttp.execute(httpClient, request)
        val features = json.getAsJsonArray("features") ?: return emptyList()
        return features.mapNotNull { feature ->
            val properties = feature.takeIf { it.isJsonObject }
                ?.asJsonObject?.getAsJsonObject("properties") ?: return@mapNotNull null
            val street = properties.string("street")?.takeIf { it.isNotBlank() }
                ?: properties.string("name")?.takeIf {
                    properties.string("type") == "street" || properties.string("osm_key") == "highway"
                }
                ?: return@mapNotNull null
            val line1 = listOfNotNull(
                street,
                properties.string("housenumber")?.takeIf { it.isNotBlank() },
            ).joinToString(" ")
            AddressSuggestion(
                line1 = line1,
                postalCode = properties.string("postcode")?.takeIf { it.isNotBlank() },
                city = properties.string("city") ?: properties.string("town")
                    ?: properties.string("village"),
                state = properties.string("state")?.takeIf { it.isNotBlank() },
                countryCode = properties.string("countrycode")?.uppercase(Locale.US),
            )
        }.distinctBy {
            listOf(it.line1, it.postalCode, it.city, it.countryCode)
        }
    }

    private companion object {
        const val DEFAULT_BASE_URL = "https://photon.komoot.io/"
    }
}
