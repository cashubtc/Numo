package com.electricdreams.numo.core.util

import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

object LnUrlClient {
    private val client = OkHttpClient()
    private val gson = Gson()

    data class LnUrlPayResponse(
        val callback: String,
        val maxSendable: Long,
        val minSendable: Long,
        val metadata: String,
        val tag: String
    )

    fun fetchLnUrlDetails(address: String): LnUrlPayResponse? {
        val url = convertAddressToUrl(address) ?: return null
        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val json = response.body?.string() ?: return null
                return gson.fromJson(json, LnUrlPayResponse::class.java)
            }
        } catch (e: Exception) {
            return null
        }
    }

    private fun convertAddressToUrl(address: String): String? {
        val parts = address.split("@")
        if (parts.size != 2) return null
        val username = parts[0]
        val domain = parts[1]
        
        // Reject invalid domain characters that could lead to SSRF or path injection
        if (domain.isBlank() || !domain.contains(".") || domain.contains("/") || domain.contains("#") || domain.contains("?")) {
            return null
        }
        
        return okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host(domain)
            .addPathSegment(".well-known")
            .addPathSegment("lnurlp")
            .addPathSegment(username)
            .build()
            .toString()
    }
}
