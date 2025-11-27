package com.electricdreams.shellshock.feature.tips

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Manages tip settings and presets.
 * Singleton pattern for easy access throughout the app.
 */
class TipsManager private constructor(private val context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    
    /**
     * Whether tips are enabled for the checkout flow.
     */
    var tipsEnabled: Boolean
        get() = prefs.getBoolean(KEY_TIPS_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_TIPS_ENABLED, value).apply()
        }
    
    /**
     * Get the list of tip presets (percentage values).
     * Returns default values if not configured.
     */
    fun getTipPresets(): List<Int> {
        val json = prefs.getString(KEY_TIP_PRESETS, null)
        return if (json != null) {
            try {
                val type = object : TypeToken<List<Int>>() {}.type
                gson.fromJson(json, type)
            } catch (e: Exception) {
                DEFAULT_PRESETS
            }
        } else {
            DEFAULT_PRESETS
        }
    }
    
    /**
     * Set the tip presets (percentage values).
     * Maximum of 4 presets allowed.
     */
    fun setTipPresets(presets: List<Int>) {
        val validPresets = presets.take(MAX_PRESETS).filter { it in 1..100 }
        val json = gson.toJson(validPresets)
        prefs.edit().putString(KEY_TIP_PRESETS, json).apply()
    }
    
    /**
     * Add a new preset if there's room.
     * Returns true if added successfully, false if at max capacity.
     */
    fun addPreset(percentage: Int): Boolean {
        if (percentage !in 1..100) return false
        
        val current = getTipPresets().toMutableList()
        if (current.size >= MAX_PRESETS) return false
        if (current.contains(percentage)) return false
        
        current.add(percentage)
        current.sort()
        setTipPresets(current)
        return true
    }
    
    /**
     * Remove a preset by percentage value.
     */
    fun removePreset(percentage: Int) {
        val current = getTipPresets().toMutableList()
        current.remove(percentage)
        setTipPresets(current)
    }
    
    /**
     * Update a preset at a specific index.
     */
    fun updatePreset(index: Int, newPercentage: Int) {
        if (newPercentage !in 1..100) return
        
        val current = getTipPresets().toMutableList()
        if (index !in current.indices) return
        
        current[index] = newPercentage
        current.sort()
        setTipPresets(current)
    }
    
    /**
     * Reset presets to defaults.
     */
    fun resetToDefaults() {
        setTipPresets(DEFAULT_PRESETS)
    }
    
    /**
     * Check if we can add more presets.
     */
    fun canAddMorePresets(): Boolean {
        return getTipPresets().size < MAX_PRESETS
    }
    
    companion object {
        private const val PREFS_NAME = "TipsSettings"
        private const val KEY_TIPS_ENABLED = "tips_enabled"
        private const val KEY_TIP_PRESETS = "tip_presets"
        
        const val MAX_PRESETS = 4
        val DEFAULT_PRESETS = listOf(5, 10, 15, 20)
        
        @Volatile
        private var instance: TipsManager? = null
        
        fun getInstance(context: Context): TipsManager {
            return instance ?: synchronized(this) {
                instance ?: TipsManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
