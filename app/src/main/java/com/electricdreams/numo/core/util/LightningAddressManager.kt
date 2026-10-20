package com.electricdreams.numo.core.util

import android.content.Context
import android.content.SharedPreferences

/**
 * Centralized manager for the user's Lightning withdrawal address.
 * 
 * This consolidates the lightning address storage so that:
 * - Auto-withdraw settings use the same address as manual withdrawals
 * - The address is pre-filled in both places
 * - Changes in one place are reflected everywhere
 * 
 * This is a singleton to ensure consistent access across the app.
 */
class LightningAddressManager private constructor(context: Context) {

    companion object {
        private const val PREFS_NAME = "LightningAddressPreferences"
        private const val KEY_LIGHTNING_ADDRESS = "lightningAddress"

        @Volatile
        private var instance: LightningAddressManager? = null

        fun getInstance(context: Context): LightningAddressManager {
            return instance ?: synchronized(this) {
                instance ?: LightningAddressManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Get the saved lightning address.
     * @return The saved lightning address, or empty string if not set.
     */
    fun getLightningAddress(): String {
        return prefs.getString(KEY_LIGHTNING_ADDRESS, "") ?: ""
    }

    /**
     * Save the lightning address.
     * @param address The lightning address to save (e.g., "user@getalby.com")
     */
    fun setLightningAddress(address: String) {
        prefs.edit().putString(KEY_LIGHTNING_ADDRESS, address.trim()).apply()
    }

    /**
     * Check if a lightning address has been configured.
     * @return True if a non-empty lightning address is saved.
     */
    fun hasLightningAddress(): Boolean {
        return getLightningAddress().isNotBlank()
    }

    /**
     * Clear the saved lightning address.
     */
    fun clearLightningAddress() {
        prefs.edit().remove(KEY_LIGHTNING_ADDRESS).apply()
    }

    /**
     * Validate a lightning address format.
     * Uses the same hostname validation as the LNURL lookup to reject incomplete input.
     * @param address The address to validate.
     * @return True if the address appears to be valid.
     */
    fun isValidLightningAddress(address: String): Boolean {
        return LnUrlClient.convertAddressToUrl(address) != null
    }
}
