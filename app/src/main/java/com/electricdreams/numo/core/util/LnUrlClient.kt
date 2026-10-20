package com.electricdreams.numo.core.util

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

object LnUrlClient {
    private const val TAG = "LnUrlClient"
    private val client = OkHttpClient()
    private val gson = Gson()

    data class LnUrlPayResponse(
        val callback: String,
        val maxSendable: Long,
        val minSendable: Long,
        val metadata: String,
        val tag: String
    )

    fun fetchLnUrlDetails(address: String): LnUrlPayResponse? = fetchLnUrlDetails(address, client)

    internal fun fetchLnUrlDetails(address: String, httpClient: OkHttpClient): LnUrlPayResponse? {
        try {
            val url = convertAddressToUrl(address) ?: return null
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val json = response.body?.string() ?: return null
                return gson.fromJson(json, LnUrlPayResponse::class.java)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Unable to fetch Lightning address details", e)
            return null
        }
    }

    /** Shared format validation and URL construction; incomplete editor input returns null. */
    internal fun convertAddressToUrl(address: String): String? {
        val parts = address.trim().split("@")
        if (parts.size != 2) return null
        val username = parts[0]
        val domain = parts[1]
        if (username.isBlank() || username.any { it.isWhitespace() }) return null

        if (!domain.contains(".") || domain.split('.').any { it.isBlank() } ||
            domain.any { it.isWhitespace() || it in "/#?" }
        ) {
            return null
        }

        return try {
            HttpUrl.Builder()
                .scheme("https")
                .host(domain)
                .addPathSegment(".well-known")
                .addPathSegment("lnurlp")
                .addPathSegment(username)
                .build()
                .toString()
        } catch (e: IllegalArgumentException) {
            Log.d(TAG, "Invalid Lightning address host", e)
            null
        }
    }
}
