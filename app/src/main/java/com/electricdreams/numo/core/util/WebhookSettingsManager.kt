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

    data class WebhookEndpointConfig(
        val url: String,
        val authKey: String? = null,
    ) {
        fun hasAuthKey(): Boolean = !authKey.isNullOrBlank()
    }

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

    fun getEndpoints(): List<WebhookEndpointConfig> {
        val json = prefs.getString(KEY_ENDPOINTS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<WebhookEndpointConfig>>() {}.type
            gson.fromJson<List<WebhookEndpointConfig>>(json, type)
                ?.filter { it.url.isNotBlank() }
                ?.map { endpoint ->
                    endpoint.copy(
                        url = normalizeEndpointUrl(endpoint.url) ?: endpoint.url,
                        authKey = normalizeAuthKey(endpoint.authKey),
                    )
                }
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addEndpoint(rawUrl: String, rawAuthKey: String? = null): SaveResult {
        val normalizedUrl = normalizeEndpointUrl(rawUrl) ?: return SaveResult.INVALID_URL
        val normalizedAuthKey = normalizeAuthKey(rawAuthKey)
        val endpoints = getEndpoints().toMutableList()
        if (endpoints.any { it.url == normalizedUrl }) {
            return SaveResult.DUPLICATE
        }
        endpoints.add(
            WebhookEndpointConfig(
                url = normalizedUrl,
                authKey = normalizedAuthKey,
            ),
        )
        saveEndpoints(endpoints)
        return SaveResult.SUCCESS
    }

    fun updateEndpoint(
        currentEndpoint: String,
        newRawUrl: String,
        newRawAuthKey: String?,
    ): SaveResult {
        val normalizedCurrent = normalizeEndpointUrl(currentEndpoint) ?: return SaveResult.NOT_FOUND
        val normalizedNewUrl = normalizeEndpointUrl(newRawUrl) ?: return SaveResult.INVALID_URL
        val normalizedNewAuthKey = normalizeAuthKey(newRawAuthKey)
        val endpoints = getEndpoints().toMutableList()
        val currentIndex = endpoints.indexOfFirst { it.url == normalizedCurrent }

        if (currentIndex < 0) {
            return SaveResult.NOT_FOUND
        }

        if (normalizedCurrent == normalizedNewUrl &&
            endpoints[currentIndex].authKey == normalizedNewAuthKey
        ) {
            return SaveResult.SUCCESS
        }

        if (normalizedCurrent != normalizedNewUrl && endpoints.any { it.url == normalizedNewUrl }) {
            return SaveResult.DUPLICATE
        }

        endpoints[currentIndex] = WebhookEndpointConfig(
            url = normalizedNewUrl,
            authKey = normalizedNewAuthKey,
        )
        saveEndpoints(endpoints)
        return SaveResult.SUCCESS
    }

    fun updateEndpoint(currentEndpoint: String, newRawUrl: String): SaveResult {
        val existingEndpoint = getEndpoint(currentEndpoint) ?: return SaveResult.NOT_FOUND
        return updateEndpoint(
            currentEndpoint = currentEndpoint,
            newRawUrl = newRawUrl,
            newRawAuthKey = existingEndpoint.authKey,
        )
    }

    fun updateAuthKey(endpointUrl: String, newRawAuthKey: String?): SaveResult {
        val normalizedUrl = normalizeEndpointUrl(endpointUrl) ?: return SaveResult.NOT_FOUND
        val endpoints = getEndpoints().toMutableList()
        val endpointIndex = endpoints.indexOfFirst { it.url == normalizedUrl }

        if (endpointIndex < 0) {
            return SaveResult.NOT_FOUND
        }

        endpoints[endpointIndex] = endpoints[endpointIndex].copy(
            authKey = normalizeAuthKey(newRawAuthKey),
        )
        saveEndpoints(endpoints)
        return SaveResult.SUCCESS
    }

    fun getEndpoint(endpointUrl: String): WebhookEndpointConfig? {
        val normalizedUrl = normalizeEndpointUrl(endpointUrl) ?: return null
        return getEndpoints().firstOrNull { it.url == normalizedUrl }
    }

    fun removeEndpoint(endpoint: String): Boolean {
        val normalized = normalizeEndpointUrl(endpoint) ?: return false
        val endpoints = getEndpoints().toMutableList()
        val removed = endpoints.removeAll { it.url == normalized }
        if (removed) {
            saveEndpoints(endpoints)
        }
        return removed
    }

    fun isValidEndpoint(rawUrl: String): Boolean = normalizeEndpointUrl(rawUrl) != null

    private fun saveEndpoints(endpoints: List<WebhookEndpointConfig>) {
        prefs.edit().putString(KEY_ENDPOINTS, gson.toJson(endpoints)).apply()
    }

    private fun normalizeAuthKey(rawAuthKey: String?): String? {
        val normalized = rawAuthKey?.trim().orEmpty()
        return if (normalized.isBlank()) null else normalized
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
