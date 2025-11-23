package com.electricdreams.shellshock.core.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

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

        // Default mints
        private val DEFAULT_MINTS: Set<String> = setOf(
            "https://mint.minibits.cash/Bitcoin",
            "https://mint.chorus.community",
            "https://mint.cubabitcoin.org",
            "https://mint.coinos.io",
        )

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

    private var listener: MintChangeListener? = null

    init {
        Log.d(TAG, "Initialized with ${allowedMints.size} allowed mints")
    }

    /** Set a listener to be notified when allowed mints change. */
    fun setMintChangeListener(listener: MintChangeListener?) {
        this.listener = listener
    }

    /** Get the list of allowed mints. */
    fun getAllowedMints(): List<String> = ArrayList(allowedMints)

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
            saveChanges()
            Log.d(TAG, "Removed mint from allowed list: $url")
            listener?.onMintsChanged(getAllowedMints())
        }

        return changed
    }

    /** Reset allowed mints to the default list. */
    fun resetToDefaults() {
        allowedMints = HashSet(DEFAULT_MINTS)
        saveChanges()
        Log.d(TAG, "Reset mints to default list")
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

    /** Save current mints to preferences. */
    private fun saveChanges() {
        preferences.edit().putStringSet(KEY_MINTS, allowedMints).apply()
    }

    /**
     * Normalize mint URL to ensure consistent format:
     * - Trim whitespace
     * - Remove trailing slash if present
     */
    private fun normalizeMintUrl(url: String): String {
        var normalized = url.trim()
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length - 1)
        }
        return normalized
    }
}
