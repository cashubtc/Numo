package com.electricdreams.numo.feature.baskets

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Manages preset basket names for quick selection when saving baskets.
 * Perfect for restaurant tables, customer names, or order types.
 * 
 * Singleton pattern for easy access throughout the app.
 */
class BasketNamesManager private constructor(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    
    /**
     * Get the list of preset basket names.
     * Returns empty list if none configured (no defaults).
     */
    fun getPresetNames(): List<String> {
        val json = prefs.getString(KEY_PRESET_NAMES, null)
        return if (json != null) {
            try {
                val type = object : TypeToken<List<String>>() {}.type
                gson.fromJson(json, type)
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
    }
    
    /**
     * Set the preset basket names.
     */
    fun setPresetNames(names: List<String>) {
        val validNames = names.filter { it.isNotBlank() }.take(MAX_PRESETS)
        val json = gson.toJson(validNames)
        prefs.edit().putString(KEY_PRESET_NAMES, json).apply()
    }
    
    /**
     * Add a new preset name if there's room.
     * Returns true if added successfully.
     */
    fun addPresetName(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return false
        
        val current = getPresetNames().toMutableList()
        if (current.size >= MAX_PRESETS) return false
        if (current.any { it.equals(trimmed, ignoreCase = true) }) return false
        
        current.add(trimmed)
        setPresetNames(current)
        return true
    }
    
    /**
     * Remove a preset name.
     */
    fun removePresetName(name: String) {
        val current = getPresetNames().toMutableList()
        current.removeAll { it.equals(name, ignoreCase = true) }
        setPresetNames(current)
    }
    
    /**
     * Update a preset at a specific index.
     */
    fun updatePresetName(index: Int, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isBlank()) return
        
        val current = getPresetNames().toMutableList()
        if (index !in current.indices) return
        
        current[index] = trimmed
        setPresetNames(current)
    }
    
    /**
     * Clear all preset names.
     */
    fun clearAll() {
        setPresetNames(emptyList())
    }
    
    /**
     * Check if we can add more presets.
     */
    fun canAddMore(): Boolean {
        return getPresetNames().size < MAX_PRESETS
    }
    
    /**
     * Check if there are any presets configured.
     */
    fun hasPresets(): Boolean {
        return getPresetNames().isNotEmpty()
    }
    
    companion object {
        private const val PREFS_NAME = "BasketNamesSettings"
        private const val KEY_PRESET_NAMES = "preset_names"
        
        const val MAX_PRESETS = 12 // Allow more presets for tables
        
        @Volatile
        private var instance: BasketNamesManager? = null
        
        fun getInstance(context: Context): BasketNamesManager {
            return instance ?: synchronized(this) {
                instance ?: BasketNamesManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
