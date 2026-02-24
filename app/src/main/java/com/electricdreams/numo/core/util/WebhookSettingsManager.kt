package com.electricdreams.numo.core.util

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.net.URI
import java.util.Locale

/**
 * Stores and validates configured webhook endpoints.
 */
class WebhookSettingsManager private constructor(context: Context) {

    enum class SaveResult {
        SUCCESS,
        INVALID_URL,
        DUPLICATE,
        NOT_FOUND,
    }

    companion object {
        private const val PREFS_NAME = "WebhookSettings"
        private const val KEY_ENDPOINTS = "endpoints"

        @Volatile
        private var instance: WebhookSettingsManager? = null

        fun getInstance(context: Context): WebhookSettingsManager {
            return instance ?: synchronized(this) {
                instance ?: WebhookSettingsManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun getEndpoints(): List<String> {
        val json = prefs.getString(KEY_ENDPOINTS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson<List<String>>(json, type)?.filter { it.isNotBlank() } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addEndpoint(rawUrl: String): SaveResult {
        val normalized = normalizeEndpointUrl(rawUrl) ?: return SaveResult.INVALID_URL
        val endpoints = getEndpoints().toMutableList()
        if (endpoints.contains(normalized)) {
            return SaveResult.DUPLICATE
        }
        endpoints.add(normalized)
        saveEndpoints(endpoints)
        return SaveResult.SUCCESS
    }

    fun updateEndpoint(currentEndpoint: String, newRawUrl: String): SaveResult {
        val normalizedCurrent = normalizeEndpointUrl(currentEndpoint) ?: return SaveResult.NOT_FOUND
        val normalizedNew = normalizeEndpointUrl(newRawUrl) ?: return SaveResult.INVALID_URL
        val endpoints = getEndpoints().toMutableList()
        val currentIndex = endpoints.indexOf(normalizedCurrent)

        if (currentIndex < 0) {
            return SaveResult.NOT_FOUND
        }

        if (normalizedCurrent == normalizedNew) {
            return SaveResult.SUCCESS
        }

        if (endpoints.contains(normalizedNew)) {
            return SaveResult.DUPLICATE
        }

        endpoints[currentIndex] = normalizedNew
        saveEndpoints(endpoints)
        return SaveResult.SUCCESS
    }

    fun removeEndpoint(endpoint: String): Boolean {
        val normalized = normalizeEndpointUrl(endpoint) ?: return false
        val endpoints = getEndpoints().toMutableList()
        val removed = endpoints.remove(normalized)
        if (removed) {
            saveEndpoints(endpoints)
        }
        return removed
    }

    fun isValidEndpoint(rawUrl: String): Boolean = normalizeEndpointUrl(rawUrl) != null

    private fun saveEndpoints(endpoints: List<String>) {
        prefs.edit().putString(KEY_ENDPOINTS, gson.toJson(endpoints)).apply()
    }

    private fun normalizeEndpointUrl(rawUrl: String): String? {
        var normalized = rawUrl.trim()
        if (normalized.isEmpty()) {
            return null
        }

        if (!normalized.contains("://")) {
            normalized = "https://$normalized"
        }

        return try {
            val uri = URI(normalized)
            val scheme = (uri.scheme ?: return null).lowercase(Locale.ROOT)
            if (scheme != "http" && scheme != "https") {
                return null
            }

            val host = (uri.host ?: return null).lowercase(Locale.ROOT)
            val userInfo = uri.userInfo?.let { "$it@" } ?: ""
            val port = if (uri.port != -1) ":${uri.port}" else ""
            val path = (uri.rawPath ?: "").let {
                if (it == "/") "" else it
            }
            val query = uri.rawQuery?.let { "?$it" } ?: ""
            val fragment = uri.rawFragment?.let { "#$it" } ?: ""

            "$scheme://$userInfo$host$port$path$query$fragment".removeSuffix("/")
        } catch (_: Exception) {
            null
        }
    }
}
