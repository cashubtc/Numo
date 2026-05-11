package com.electricdreams.numo.core.util

import android.content.Context
import android.util.Log
import com.electricdreams.numo.core.cashu.CashuWalletManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Shared service for validating, fetching, and caching mint profile metadata.
 */
class MintProfileService private constructor(context: Context) {

    enum class ErrorType {
        INVALID_URL,
        NETWORK,
        INVALID_RESPONSE,
    }

    data class ValidationResult(
        val isValid: Boolean,
        val normalizedUrl: String?,
        val errorType: ErrorType? = null,
    )

    data class ProfileSyncResult(
        val normalizedUrl: String,
        val success: Boolean,
        val displayName: String?,
        val iconCached: Boolean,
        val errorType: ErrorType? = null,
    )

    companion object {
        private const val TAG = "MintProfileService"

        @Volatile
        private var instance: MintProfileService? = null

        @JvmStatic
        @Synchronized
        fun getInstance(context: Context): MintProfileService {
            if (instance == null) {
                instance = MintProfileService(context.applicationContext)
            }
            return instance as MintProfileService
        }
    }

    private val appContext = context.applicationContext
    private val mintManager = MintManager.getInstance(appContext)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    init {
        MintIconCache.initialize(appContext)
    }

    fun normalizeUrl(rawUrl: String): String {
        var normalized = rawUrl.trim()
        if (normalized.isEmpty()) {
            return normalized
        }

        if (!normalized.contains("://")) {
            normalized = "https://$normalized"
        }

        val sanitized = try {
            val uri = URI(normalized)
            val scheme = uri.scheme ?: "https"
            val host = uri.host ?: return normalized.removeSuffix("/")
            val userInfo = uri.userInfo?.let { "$it@" } ?: ""
            val portSegment = if (uri.port != -1) ":${uri.port}" else ""
            val path = uri.rawPath ?: ""
            val query = uri.rawQuery?.let { "?$it" } ?: ""
            val fragment = uri.rawFragment?.let { "#$it" } ?: ""

            buildString {
                append(scheme)
                append("://")
                append(userInfo)
                append(host.lowercase(Locale.ROOT))
                append(portSegment)
                append(path)
                append(query)
                append(fragment)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fully normalize mint URL: $rawUrl", e)
            normalized
        }

        return sanitized.removeSuffix("/")
    }

    suspend fun validateMintUrl(rawUrl: String): ValidationResult = withContext(Dispatchers.IO) {
        val normalizedUrl = normalizeUrl(rawUrl)

        if (normalizedUrl.isBlank() || !hasValidHost(normalizedUrl)) {
            return@withContext ValidationResult(
                isValid = false,
                normalizedUrl = null,
                errorType = ErrorType.INVALID_URL,
            )
        }

        val infoUrl = "$normalizedUrl/v1/info"
        try {
            val request = Request.Builder().url(infoUrl).get().build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful && response.code == 200) {
                    ValidationResult(
                        isValid = true,
                        normalizedUrl = normalizedUrl,
                    )
                } else {
                    ValidationResult(
                        isValid = false,
                        normalizedUrl = normalizedUrl,
                        errorType = ErrorType.INVALID_RESPONSE,
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Mint URL validation failed for $normalizedUrl", e)
            ValidationResult(
                isValid = false,
                normalizedUrl = normalizedUrl,
                errorType = ErrorType.NETWORK,
            )
        }
    }

    suspend fun fetchAndStoreMintProfile(
        rawUrl: String,
        validateEndpoint: Boolean = false,
        storeInCache: Boolean = true,
    ): ProfileSyncResult = withContext(Dispatchers.IO) {
        val normalizedUrl = normalizeUrl(rawUrl)

        if (normalizedUrl.isBlank() || !hasValidHost(normalizedUrl)) {
            return@withContext ProfileSyncResult(
                normalizedUrl = normalizedUrl,
                success = false,
                displayName = null,
                iconCached = false,
                errorType = ErrorType.INVALID_URL,
            )
        }

        if (validateEndpoint) {
            val validation = validateMintUrl(normalizedUrl)
            if (!validation.isValid) {
                return@withContext ProfileSyncResult(
                    normalizedUrl = normalizedUrl,
                    success = false,
                    displayName = null,
                    iconCached = false,
                    errorType = validation.errorType,
                )
            }
        }

        var infoJson: String? = null
        var displayName: String? = null
        var iconUrl: String? = null

        try {
            val cdkInfo = CashuWalletManager.fetchMintInfo(normalizedUrl)
            if (cdkInfo != null) {
                infoJson = CashuWalletManager.mintInfoToJson(cdkInfo)
                displayName = cdkInfo.name?.trim()?.takeIf { it.isNotEmpty() }
                iconUrl = cdkInfo.iconUrl?.trim()?.takeIf { it.isNotEmpty() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "CDK mint info fetch failed for $normalizedUrl", e)
        }

        if (infoJson == null) {
            val networkResult = fetchMintInfoFromNetwork(normalizedUrl)
            if (networkResult.infoJson == null) {
                return@withContext ProfileSyncResult(
                    normalizedUrl = normalizedUrl,
                    success = false,
                    displayName = null,
                    iconCached = false,
                    errorType = networkResult.errorType,
                )
            }

            val canonicalInfo = canonicalizeMintInfoJson(networkResult.infoJson)
            infoJson = canonicalInfo.toString()
            displayName = canonicalInfo.optString("name", "").trim().ifEmpty { null }
            iconUrl = canonicalInfo.optString("iconUrl", "").trim().ifEmpty { null }
        }

        // Only store in cache if storeInCache is true
        if (storeInCache) {
            mintManager.setMintInfo(normalizedUrl, infoJson)
            Log.d("MintProfileService", "setMintInfo called for $normalizedUrl, length=${infoJson.length}")
            mintManager.setMintRefreshTimestamp(normalizedUrl)
        } else {
            Log.d("MintProfileService", "Skipped storing mint info in cache for $normalizedUrl (storeInCache=false)")
        }

        var iconCached = false
        if (!iconUrl.isNullOrBlank()) {
            iconCached = MintIconCache.downloadAndCacheIcon(normalizedUrl, iconUrl) != null
        }

        ProfileSyncResult(
            normalizedUrl = normalizedUrl,
            success = true,
            displayName = displayName,
            iconCached = iconCached,
            errorType = null,
        )
    }

    private data class NetworkMintInfoResult(
        val infoJson: JSONObject?,
        val errorType: ErrorType?,
    )

    private suspend fun fetchMintInfoFromNetwork(mintUrl: String): NetworkMintInfoResult =
        withContext(Dispatchers.IO) {
            val infoUrl = "$mintUrl/v1/info"
            try {
                val request = Request.Builder().url(infoUrl).get().build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful || response.code != 200) {
                        return@withContext NetworkMintInfoResult(
                            infoJson = null,
                            errorType = ErrorType.INVALID_RESPONSE,
                        )
                    }

                    val body = response.body?.string().orEmpty()
                    if (body.isBlank()) {
                        return@withContext NetworkMintInfoResult(
                            infoJson = null,
                            errorType = ErrorType.INVALID_RESPONSE,
                        )
                    }

                    val parsed = JSONObject(body)
                    Log.d(TAG, "Network mint info response has nuts: ${parsed.has("nuts")}")
                    if (parsed.has("nuts")) {
                        Log.d(TAG, "Network nuts: ${parsed.optJSONObject("nuts")}")
                    }
                    NetworkMintInfoResult(infoJson = parsed, errorType = null)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Network mint info fetch failed for $mintUrl", e)
                NetworkMintInfoResult(infoJson = null, errorType = ErrorType.NETWORK)
            }
        }

    private fun canonicalizeMintInfoJson(raw: JSONObject): JSONObject {
        val result = JSONObject()

        val name = raw.optString("name", "").trim()
        if (name.isNotEmpty()) {
            result.put("name", name)
        }

        val description = raw.optString("description", "").trim()
        if (description.isNotEmpty()) {
            result.put("description", description)
        }

        val descriptionLong = raw.optString("descriptionLong", raw.optString("description_long", "")).trim()
        if (descriptionLong.isNotEmpty()) {
            result.put("descriptionLong", descriptionLong)
        }

        val motd = raw.optString("motd", "").trim()
        if (motd.isNotEmpty()) {
            result.put("motd", motd)
        }

        val iconUrl = raw.optString("iconUrl", raw.optString("icon_url", "")).trim()
        if (iconUrl.isNotEmpty()) {
            result.put("iconUrl", iconUrl)
        }

        val versionObj = raw.opt("version")
        if (versionObj is JSONObject) {
            val versionName = versionObj.optString("name", "").trim()
            val version = versionObj.optString("version", "").trim()
            if (versionName.isNotEmpty() || version.isNotEmpty()) {
                val normalizedVersion = JSONObject()
                if (versionName.isNotEmpty()) {
                    normalizedVersion.put("name", versionName)
                }
                if (version.isNotEmpty()) {
                    normalizedVersion.put("version", version)
                }
                result.put("version", normalizedVersion)
            }
        }

        val contactObj = raw.opt("contact")
        if (contactObj is JSONArray) {
            result.put("contact", contactObj)
        }

        // Copy nuts section (NUT-04 and NUT-05 for mint limits)
        val nutsObj = raw.opt("nuts")
        if (nutsObj is JSONObject) {
            result.put("nuts", nutsObj)
        }

        return result
    }

    private fun hasValidHost(url: String): Boolean {
        return try {
            val uri = URI(url)
            !uri.host.isNullOrBlank()
        } catch (e: Exception) {
            false
        }
    }
}
