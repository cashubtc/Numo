package com.electricdreams.numo.core.payment

import android.util.Log
import com.electricdreams.numo.core.model.Item
import com.electricdreams.numo.core.model.PriceType
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class BtcPayPosApp(val id: String, val name: String)

object BtcPayAppsService {

    private const val TAG = "BtcPayAppsService"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun fetchPosApps(config: BTCPayConfig): List<BtcPayPosApp> {
        val url = "${config.serverUrl.trimEnd('/')}/api/v1/apps"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "token ${config.apiKey}")
            .get()
            .build()

        val (code, body) = client.newCall(request).execute().use { response ->
            response.code to (response.body?.string() ?: "")
        }

        Log.d(TAG, "fetchPosApps HTTP $code: ${body.take(200)}")

        if (code == 403 || code == 401) {
            throw Exception("HTTP $code — add btcpay.user.canviewapps permission to your API key")
        }
        if (code !in 200..299) {
            throw Exception("HTTP $code")
        }

        val parsed = try {
            JsonParser.parseString(body)
        } catch (e: Exception) {
            throw Exception("Invalid JSON response: ${body.take(80)}")
        }

        val array = when {
            parsed.isJsonArray -> parsed.asJsonArray
            else -> throw Exception("Unexpected response format: ${body.take(80)}")
        }

        return array.asSequence()
            .filter { it.isJsonObject }
            .map { it.asJsonObject }
            .filter { obj ->
                obj.str("appType") == "PointOfSale" &&
                    obj.str("storeId") == config.storeId
            }
            .mapNotNull { obj ->
                val id = obj.str("id") ?: return@mapNotNull null
                val name = obj.str("name") ?: id
                BtcPayPosApp(id, name)
            }
            .toList()
    }

    fun fetchPosItems(config: BTCPayConfig, appId: String): List<Item> {
        val url = "${config.serverUrl.trimEnd('/')}/api/v1/apps/pos/$appId"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "token ${config.apiKey}")
            .get()
            .build()

        val (code, body) = client.newCall(request).execute().use { response ->
            response.code to (response.body?.string() ?: "")
        }

        Log.d(TAG, "fetchPosItems HTTP $code: ${body.take(200)}")

        if (code !in 200..299) throw Exception("HTTP $code")

        val appData = try {
            JsonParser.parseString(body).asJsonObject
        } catch (e: Exception) {
            throw Exception("Invalid JSON response: ${body.take(80)}")
        }

        val itemsArray = appData.getAsJsonArray("items") ?: return emptyList()

        return itemsArray.asSequence()
            .filter { it.isJsonObject }
            .map { it.asJsonObject }
            .filter { !(it.get("disabled")?.asBooleanOrNull ?: false) }
            .mapNotNull { item ->
                val id = item.str("id") ?: return@mapNotNull null
                val priceType = item.str("priceType") ?: "Fixed"
                val price = if (priceType == "Topup") 0.0 else (item.get("price")?.asDoubleOrNull ?: 0.0)
                Item(
                    id = id,
                    name = item.str("title"),
                    description = item.str("description"),
                    price = price,
                    priceType = PriceType.FIAT,
                    imagePath = resolveImage(config, item.str("image")),
                )
            }
            .toList()
    }

    // --- Image resolution ---

    /**
     * Resolves a BTCPay item image to a data URI suitable for [Item.imagePath].
     *
     * BTCPay stores images as:
     *  - data URIs  ("data:image/...;base64,...")  → returned as-is
     *  - relative URLs ("/storage/...")             → prefixed with serverUrl, then downloaded
     *  - absolute URLs ("https://...")              → downloaded directly
     *
     * Returns null if the value is blank, unrecognised, or download fails.
     * All images end up as data URIs so the adapter only needs one code path.
     */
    private fun resolveImage(config: BTCPayConfig, imageValue: String?): String? {
        if (imageValue.isNullOrBlank()) return null

        // Already a data URI — use directly.
        if (imageValue.startsWith("data:")) return imageValue

        // Build an absolute URL.
        val url = when {
            imageValue.startsWith("http://") || imageValue.startsWith("https://") -> imageValue
            imageValue.startsWith("/") -> "${config.serverUrl.trimEnd('/')}$imageValue"
            imageValue.startsWith("~/") -> "${config.serverUrl.trimEnd('/')}/${imageValue.removePrefix("~/")}"
            else -> {
                Log.w(TAG, "resolveImage: unrecognised image format: ${imageValue.take(40)}")
                return null
            }
        }

        return try {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bytes = response.body?.bytes() ?: return null
                val mime = response.header("Content-Type")
                    ?.substringBefore(";")?.trim()
                    ?.takeIf { it.startsWith("image/") }
                    ?: "image/jpeg"
                "data:$mime;base64,${java.util.Base64.getEncoder().encodeToString(bytes)}"
            }
        } catch (e: Exception) {
            Log.w(TAG, "resolveImage: failed to download $url: ${e.message}")
            null
        }
    }

    // --- Safe JsonElement accessors ---

    private fun com.google.gson.JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private val JsonElement.asBooleanOrNull: Boolean?
        get() = if (isJsonPrimitive) runCatching { asBoolean }.getOrNull() else null

    private val JsonElement.asDoubleOrNull: Double?
        get() = if (isJsonPrimitive) runCatching { asDouble }.getOrNull() else null
}
