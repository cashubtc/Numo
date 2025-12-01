package com.electricdreams.numo.core.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.electricdreams.numo.core.cashu.CashuWalletManager
import com.electricdreams.numo.nostr.NostrMintBackup
import org.json.JSONObject
import java.net.URI
import java.util.Locale

/**
 * Manages allowed mints for Cashu tokens.
 */
class MintManager private constructor(context: Context) {

    interface MintChangeListener {
        fun onMintsChanged(newMints: List<String>)
    }

    companion object {
        private const val TAG = "MintManager"
        private const val PREFS_NAME = "MintPreferences"
        private const val KEY_MINTS = "allowedMints"
        private const val KEY_PREFERRED_LIGHTNING_MINT = "preferredLightningMint"
        private const val KEY_ENABLE_SWAP_UNKNOWN_MINTS = "enableSwapUnknownMints"
        private const val KEY_MINT_INFO_PREFIX = "mintInfo_"
        private const val KEY_MINT_REFRESH_PREFIX = "mintRefresh_"
        private const val REFRESH_INTERVAL_MS = 24 * 60 * 60 * 1000L // 24 hours

        // Default mints
        private val DEFAULT_MINTS: Set<String> = setOf(
            "https://mint.minibits.cash/Bitcoin",
            "https://mint.chorus.community",
            "https://mint.cubabitcoin.org",
            "https://mint.coinos.io",
        )
        
        // Default Lightning mint (first of the default mints)
        private const val DEFAULT_LIGHTNING_MINT = "https://mint.minibits.cash/Bitcoin"

        @Volatile
        private var instance: MintManager? = null

        @JvmStatic
        @Synchronized
        fun getInstance(context: Context): MintManager {
            if (instance == null) {
                instance = MintManager(context.applicationContext)
            }
            return instance as MintManager
        }
    }

    private val context: Context = context.applicationContext
    private val preferences: SharedPreferences =
        this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var allowedMints: MutableSet<String> =
        HashSet(preferences.getStringSet(KEY_MINTS, DEFAULT_MINTS) ?: DEFAULT_MINTS)

    private var preferredLightningMint: String? =
        preferences.getString(KEY_PREFERRED_LIGHTNING_MINT, null)

    private var enableSwapFromUnknownMints: Boolean =
        preferences.getBoolean(KEY_ENABLE_SWAP_UNKNOWN_MINTS, true)

    private var listener: MintChangeListener? = null

    init {
        Log.d(TAG, "Initialized with ${allowedMints.size} allowed mints")
        // Ensure preferred Lightning mint is valid (exists in allowed mints)
        val preferred = preferredLightningMint
        if (preferred == null || !allowedMints.contains(preferred)) {
            // Set to first allowed mint or default
            preferredLightningMint = allowedMints.firstOrNull() ?: DEFAULT_LIGHTNING_MINT
            savePreferredLightningMint()
        }
    }

    /** Set a listener to be notified when allowed mints change. */
    fun setMintChangeListener(listener: MintChangeListener?) {
        this.listener = listener
    }

    /** Get the list of allowed mints. */
    fun getAllowedMints(): List<String> = ArrayList(allowedMints)

    /**
     * Returns true if at least one mint is configured.
     * Used by UI to disable payment flows when no mints are available.
     */
    fun hasAnyMints(): Boolean = allowedMints.isNotEmpty()

    /**
     * Get the preferred mint for Lightning payments.
     * Falls back to the first allowed mint if not set.
     */
    fun getPreferredLightningMint(): String? {
        val preferred = preferredLightningMint
        // Return preferred if it's still in the allowed list
        if (preferred != null && allowedMints.contains(preferred)) {
            return preferred
        }
        // Otherwise return the first allowed mint
        return allowedMints.firstOrNull()
    }

    /**
     * Set the preferred mint for Lightning payments.
     * @param mintUrl The mint URL to set as preferred. Must be in the allowed list.
     * @return true if the preference was set, false if the mint is not in the allowed list.
     */
    fun setPreferredLightningMint(mintUrl: String?): Boolean {
        var url = mintUrl?.trim()
        if (url.isNullOrEmpty()) {
            Log.e(TAG, "Cannot set empty mint URL as preferred Lightning mint")
            return false
        }

        url = normalizeMintUrl(url)
        if (!allowedMints.contains(url)) {
            Log.e(TAG, "Cannot set preferred Lightning mint: $url is not in the allowed list")
            return false
        }

        preferredLightningMint = url
        savePreferredLightningMint()
        Log.d(TAG, "Set preferred Lightning mint to: $url")
        return true
    }

    /** Save preferred Lightning mint to preferences. */
    private fun savePreferredLightningMint() {
        preferences.edit().putString(KEY_PREFERRED_LIGHTNING_MINT, preferredLightningMint).apply()
    }

    /**
     * Whether the POS should accept payments from unknown mints by swapping
     * them into the configured Lightning mint.
     *
     * Default: true (current behavior).
     */
    fun isSwapFromUnknownMintsEnabled(): Boolean = enableSwapFromUnknownMints

    /** Enable or disable SwapToLightningMint for unknown-mint payments. */
    fun setSwapFromUnknownMintsEnabled(enabled: Boolean) {
        if (enableSwapFromUnknownMints == enabled) return

        enableSwapFromUnknownMints = enabled
        preferences.edit()
            .putBoolean(KEY_ENABLE_SWAP_UNKNOWN_MINTS, enabled)
            .apply()

        Log.d(TAG, "Swap from unknown mints setting changed: $enabled")
    }

    /**
     * Add a mint to the allowed list.
     * @param mintUrl The mint URL to add.
     * @return true if the mint was added, false if it was already in the list.
     */
    fun addMint(mintUrl: String?): Boolean {
        var url = mintUrl?.trim()
        if (url.isNullOrEmpty()) {
            Log.e(TAG, "Cannot add empty mint URL")
            return false
        }

        url = normalizeMintUrl(url)
        val changed = allowedMints.add(url)

        if (changed) {
            // If this is the only mint, automatically set it as the preferred Lightning mint
            if (allowedMints.size == 1) {
                preferredLightningMint = url
                savePreferredLightningMint()
                Log.d(TAG, "Automatically set first mint as preferred Lightning mint: $url")
            }
            
            saveChanges()
            Log.d(TAG, "Added mint to allowed list: $url")
            listener?.onMintsChanged(getAllowedMints())
        }

        return changed
    }

    /**
     * Remove a mint from the allowed list.
     * @param mintUrl The mint URL to remove.
     * @return true if the mint was removed, false if it wasn't in the list.
     */
    fun removeMint(mintUrl: String?): Boolean {
        var url = mintUrl?.trim()
        if (url.isNullOrEmpty()) {
            Log.e(TAG, "Cannot remove empty mint URL")
            return false
        }

        url = normalizeMintUrl(url)
        val changed = allowedMints.remove(url)

        if (changed) {
            // If the removed mint was the preferred Lightning mint, reset to first available
            if (preferredLightningMint == url) {
                preferredLightningMint = allowedMints.firstOrNull()
                savePreferredLightningMint()
                Log.d(TAG, "Preferred Lightning mint was removed, now set to: $preferredLightningMint")
            }
            saveChanges()
            Log.d(TAG, "Removed mint from allowed list: $url")
            listener?.onMintsChanged(getAllowedMints())
        }

        return changed
    }

    /** Reset allowed mints to the default list. */
    fun resetToDefaults() {
        allowedMints = HashSet(DEFAULT_MINTS)
        preferredLightningMint = DEFAULT_LIGHTNING_MINT
        saveChanges()
        savePreferredLightningMint()
        Log.d(TAG, "Reset mints to default list, preferred Lightning mint: $preferredLightningMint")
        listener?.onMintsChanged(getAllowedMints())
    }

    /**
     * Check if a mint is allowed.
     * @param mintUrl The mint URL to check.
     * @return true if the mint is in the allowed list, false otherwise.
     */
    fun isMintAllowed(mintUrl: String?): Boolean {
        var url = mintUrl?.trim()
        if (url.isNullOrEmpty()) return false

        url = normalizeMintUrl(url)
        val allowed = allowedMints.contains(url)
        if (!allowed) {
            Log.d(TAG, "Mint not allowed: $url")
        }
        return allowed
    }

    /**
     * Store mint info JSON for a mint URL.
     */
    fun setMintInfo(mintUrl: String, infoJson: String) {
        val normalized = normalizeMintUrl(mintUrl)
        preferences.edit().putString(KEY_MINT_INFO_PREFIX + normalized, infoJson).apply()
        Log.d(TAG, "Stored mint info for $normalized")
    }

    /**
     * Get stored mint info JSON for a mint URL.
     * @return The JSON string or null if not stored.
     */
    fun getMintInfo(mintUrl: String): String? {
        val normalized = normalizeMintUrl(mintUrl)
        return preferences.getString(KEY_MINT_INFO_PREFIX + normalized, null)
    }

    /**
     * Get the display name for a mint.
     * Returns the mint's name from info if available, otherwise extracts host from URL.
     */
    fun getMintDisplayName(mintUrl: String): String {
        val infoJson = getMintInfo(mintUrl)
        if (infoJson != null) {
            try {
                val json = JSONObject(infoJson)
                val name = json.optString("name", "")
                if (name.isNotEmpty()) {
                    return name
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse mint info JSON for $mintUrl", e)
            }
        }
        // Fallback to extracting host from URL
        return extractHostFromUrl(mintUrl)
    }

    /**
     * Get the icon URL for a mint.
     * Returns the iconUrl from mint info if available, otherwise null.
     */
    fun getMintIconUrl(mintUrl: String): String? {
        val infoJson = getMintInfo(mintUrl)
        if (infoJson != null) {
            try {
                val json = JSONObject(infoJson)
                val iconUrl = json.optString("iconUrl", "")
                if (iconUrl.isNotEmpty()) {
                    return iconUrl
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse mint info JSON for icon URL: $mintUrl", e)
            }
        }
        return null
    }

    /**
     * Set the last refresh timestamp for a mint.
     */
    fun setMintRefreshTimestamp(mintUrl: String, timestamp: Long = System.currentTimeMillis()) {
        val normalized = normalizeMintUrl(mintUrl)
        preferences.edit().putLong(KEY_MINT_REFRESH_PREFIX + normalized, timestamp).apply()
        Log.d(TAG, "Updated refresh timestamp for $normalized")
    }

    /**
     * Get the last refresh timestamp for a mint.
     * @return The timestamp in milliseconds, or 0 if never refreshed.
     */
    fun getMintRefreshTimestamp(mintUrl: String): Long {
        val normalized = normalizeMintUrl(mintUrl)
        return preferences.getLong(KEY_MINT_REFRESH_PREFIX + normalized, 0L)
    }

    /**
     * Check if a mint's info needs to be refreshed (older than 24 hours).
     * @return true if the mint info should be refreshed, false otherwise.
     */
    fun needsRefresh(mintUrl: String): Boolean {
        val lastRefresh = getMintRefreshTimestamp(mintUrl)
        if (lastRefresh == 0L) {
            // Never refreshed
            return true
        }
        val now = System.currentTimeMillis()
        val needsUpdate = (now - lastRefresh) > REFRESH_INTERVAL_MS
        if (needsUpdate) {
            Log.d(TAG, "Mint $mintUrl needs refresh (last: ${(now - lastRefresh) / (1000 * 60 * 60)}h ago)")
        }
        return needsUpdate
    }

    /**
     * Get list of mints that need to be refreshed (info older than 24 hours).
     * @return List of mint URLs that need refreshing.
     */
    fun getMintsNeedingRefresh(): List<String> {
        return allowedMints.filter { needsRefresh(it) }
    }

    /**
     * Extract a display-friendly host from a mint URL.
     */
    private fun extractHostFromUrl(mintUrl: String): String {
        return try {
            val uri = URI(mintUrl)
            var host = uri.host ?: return mintUrl
            if (host.startsWith("www.")) {
                host = host.substring(4)
            }
            val path = uri.path
            if (!path.isNullOrEmpty() && path != "/") {
                host + path
            } else {
                host
            }
        } catch (e: Exception) {
            mintUrl
        }
    }

    /** Save current mints to preferences and trigger Nostr backup. */
    private fun saveChanges() {
        preferences.edit().putStringSet(KEY_MINTS, allowedMints).apply()
        
        // Trigger Nostr mint backup
        triggerNostrMintBackup()
    }

    /**
     * Trigger a Nostr mint backup using keys derived from the wallet mnemonic.
     * This is called automatically when the mint list changes.
     */
    private fun triggerNostrMintBackup() {
        val mnemonic = CashuWalletManager.getMnemonic()
        if (mnemonic.isNullOrBlank()) {
            Log.w(TAG, "Cannot backup mints to Nostr: wallet mnemonic not available")
            return
        }

        val mints = getAllowedMints()
        Log.d(TAG, "Triggering Nostr mint backup for ${mints.size} mints")

        NostrMintBackup.publishMintBackup(mnemonic, mints) { result ->
            if (result.success) {
                Log.i(TAG, "✅ Nostr mint backup successful!")
                Log.i(TAG, "   Event ID: ${result.eventId}")
                Log.i(TAG, "   Published to ${result.successfulRelays.size} relays: ${result.successfulRelays.joinToString(", ")}")
                if (result.failedRelays.isNotEmpty()) {
                    Log.w(TAG, "   Failed relays: ${result.failedRelays.joinToString(", ")}")
                }
            } else {
                Log.e(TAG, "❌ Nostr mint backup failed: ${result.error}")
                Log.e(TAG, "   Failed relays: ${result.failedRelays.joinToString(", ")}")
            }
        }
    }

    /**
     * Normalize mint URL to ensure consistent format:
     * - Trim whitespace
     * - Ensure https:// when protocol missing
     * - Lowercase host while leaving path/query untouched
     * - Remove trailing slash for stable comparisons
     */
    private fun normalizeMintUrl(url: String): String {
        var normalized = url.trim()
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
            Log.w(TAG, "Failed to fully normalize mint URL: $url", e)
            normalized
        }

        return sanitized.removeSuffix("/")
    }
}
