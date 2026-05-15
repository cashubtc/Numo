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
        if (parts[1].isBlank() || !parts[1].contains(".")) return null
        return "https://${parts[1]}/.well-known/lnurlp/${parts[0]}"
    }
}
