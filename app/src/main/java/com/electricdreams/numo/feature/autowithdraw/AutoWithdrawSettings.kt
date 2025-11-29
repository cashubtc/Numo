package com.electricdreams.numo.feature.autowithdraw

import android.content.Context
import android.content.SharedPreferences
import com.electricdreams.numo.core.util.LightningAddressManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Settings for automatic withdrawals for a specific mint.
 */
data class MintWithdrawSettings(
    val mintUrl: String,
    val enabled: Boolean = false,
    val thresholdSats: Long = 10000, // Default 10k sats threshold
    val withdrawPercentage: Int = 95, // Default 95% withdrawal (5% buffer for fees)
    val lightningAddress: String = ""
)

/**
 * Manages auto-withdrawal settings persistence.
 */
class AutoWithdrawSettingsManager private constructor(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "AutoWithdrawPreferences"
        private const val KEY_GLOBAL_ENABLED = "globalEnabled"
        private const val KEY_MINT_SETTINGS = "mintSettings"
        private const val KEY_DEFAULT_THRESHOLD = "defaultThreshold"
        private const val KEY_DEFAULT_PERCENTAGE = "defaultPercentage"
        // Note: Lightning address is now managed by LightningAddressManager for consistency
        // across auto-withdraw and manual withdraw features
        
        const val MIN_THRESHOLD_SATS = 1000L // Minimum 1k sats
        const val MAX_THRESHOLD_SATS = 1000000L // Maximum 1M sats
        const val MIN_WITHDRAW_PERCENTAGE = 50
        const val MAX_WITHDRAW_PERCENTAGE = 98
        const val DEFAULT_THRESHOLD_SATS = 50000L // 50k sats (₿50,000)
        const val DEFAULT_WITHDRAW_PERCENTAGE = 95

        @Volatile
        private var instance: AutoWithdrawSettingsManager? = null

        fun getInstance(context: Context): AutoWithdrawSettingsManager {
            return instance ?: synchronized(this) {
                instance ?: AutoWithdrawSettingsManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val lightningAddressManager = LightningAddressManager.getInstance(context)

    /**
     * Check if auto-withdraw is globally enabled.
     */
    fun isGloballyEnabled(): Boolean = prefs.getBoolean(KEY_GLOBAL_ENABLED, false)

    /**
     * Set global auto-withdraw enabled state.
     */
    fun setGloballyEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_GLOBAL_ENABLED, enabled).apply()
    }

    /**
     * Get the default threshold in sats.
     */
    fun getDefaultThreshold(): Long = prefs.getLong(KEY_DEFAULT_THRESHOLD, DEFAULT_THRESHOLD_SATS)

    /**
     * Set the default threshold in sats.
     */
    fun setDefaultThreshold(threshold: Long) {
        val clamped = threshold.coerceIn(MIN_THRESHOLD_SATS, MAX_THRESHOLD_SATS)
        prefs.edit().putLong(KEY_DEFAULT_THRESHOLD, clamped).apply()
    }

    /**
     * Get the default withdrawal percentage.
     */
    fun getDefaultPercentage(): Int = prefs.getInt(KEY_DEFAULT_PERCENTAGE, DEFAULT_WITHDRAW_PERCENTAGE)

    /**
     * Set the default withdrawal percentage.
     */
    fun setDefaultPercentage(percentage: Int) {
        val clamped = percentage.coerceIn(MIN_WITHDRAW_PERCENTAGE, MAX_WITHDRAW_PERCENTAGE)
        prefs.edit().putInt(KEY_DEFAULT_PERCENTAGE, clamped).apply()
    }

    /**
     * Get the default lightning address.
     * Uses the shared LightningAddressManager for consistency with manual withdrawals.
     */
    fun getDefaultLightningAddress(): String = lightningAddressManager.getLightningAddress()

    /**
     * Set the default lightning address.
     * Uses the shared LightningAddressManager for consistency with manual withdrawals.
     */
    fun setDefaultLightningAddress(address: String) {
        lightningAddressManager.setLightningAddress(address)
    }

    /**
     * Get settings for a specific mint.
     * Note: The lightning address uses the shared global address from LightningAddressManager
     * unless a per-mint override is stored (for future per-mint address support).
     */
    fun getMintSettings(mintUrl: String): MintWithdrawSettings {
        val allSettings = getAllMintSettings()
        val storedSettings = allSettings[mintUrl]
        
        // Use shared lightning address from LightningAddressManager
        val sharedLightningAddress = lightningAddressManager.getLightningAddress()
        
        return storedSettings?.copy(
            // Override with shared lightning address if the stored one is empty
            lightningAddress = if (storedSettings.lightningAddress.isNotBlank()) 
                storedSettings.lightningAddress 
            else 
                sharedLightningAddress
        ) ?: MintWithdrawSettings(
            mintUrl = mintUrl,
            enabled = false,
            thresholdSats = getDefaultThreshold(),
            withdrawPercentage = getDefaultPercentage(),
            lightningAddress = sharedLightningAddress
        )
    }

    /**
     * Save settings for a specific mint.
     */
    fun saveMintSettings(settings: MintWithdrawSettings) {
        val allSettings = getAllMintSettings().toMutableMap()
        allSettings[settings.mintUrl] = settings
        saveMintSettingsMap(allSettings)
    }

    /**
     * Get all mint settings.
     */
    fun getAllMintSettings(): Map<String, MintWithdrawSettings> {
        val json = prefs.getString(KEY_MINT_SETTINGS, null) ?: return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, MintWithdrawSettings>>() {}.type
            gson.fromJson(json, type) ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * Save all mint settings.
     */
    private fun saveMintSettingsMap(settings: Map<String, MintWithdrawSettings>) {
        val json = gson.toJson(settings)
        prefs.edit().putString(KEY_MINT_SETTINGS, json).apply()
    }

    /**
     * Check if auto-withdraw is enabled for a specific mint.
     * 
     * When globally enabled, all mints are eligible for auto-withdraw unless
     * explicitly disabled per-mint. The per-mint settings are used for custom
     * thresholds/percentages, but the default behavior uses global defaults.
     */
    fun isEnabledForMint(mintUrl: String): Boolean {
        if (!isGloballyEnabled()) {
            android.util.Log.d("AutoWithdrawSettings", "isEnabledForMint($mintUrl): false - globally disabled")
            return false
        }
        // When globally enabled, use per-mint enabled if set, otherwise default to true
        val mintSettings = getAllMintSettings()[mintUrl]
        val enabled = mintSettings?.enabled ?: true // Default to true when globally enabled
        android.util.Log.d("AutoWithdrawSettings", "isEnabledForMint($mintUrl): $enabled (per-mint setting exists: ${mintSettings != null})")
        return enabled
    }

    /**
     * Check if a mint balance exceeds the threshold for auto-withdrawal.
     */
    fun shouldTriggerWithdrawal(mintUrl: String, currentBalance: Long): Boolean {
        android.util.Log.d("AutoWithdrawSettings", "shouldTriggerWithdrawal check: mint=$mintUrl, balance=$currentBalance")
        
        if (!isEnabledForMint(mintUrl)) {
            android.util.Log.d("AutoWithdrawSettings", "shouldTriggerWithdrawal: false - not enabled for mint")
            return false
        }
        
        val settings = getMintSettings(mintUrl)
        android.util.Log.d("AutoWithdrawSettings", "shouldTriggerWithdrawal: settings=$settings")
        
        if (settings.lightningAddress.isBlank()) {
            android.util.Log.d("AutoWithdrawSettings", "shouldTriggerWithdrawal: false - no lightning address configured")
            return false
        }
        
        val shouldTrigger = currentBalance >= settings.thresholdSats
        android.util.Log.d("AutoWithdrawSettings", "shouldTriggerWithdrawal: balance=$currentBalance >= threshold=${settings.thresholdSats} = $shouldTrigger")
        return shouldTrigger
    }

    /**
     * Calculate the amount to withdraw based on settings.
     */
    fun calculateWithdrawAmount(mintUrl: String, currentBalance: Long): Long {
        val settings = getMintSettings(mintUrl)
        return (currentBalance * settings.withdrawPercentage / 100)
    }
}
