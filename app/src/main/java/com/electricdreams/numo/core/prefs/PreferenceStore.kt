package com.electricdreams.numo.core.prefs

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * Centralizes SharedPreferences access with explicit synchronization.
 *
 * This avoids different threads instantiating their own SharedPreferences
 * instances for the same file, which could lead to race conditions.
 */
object PreferenceStore {

    private const val APP_PREFS_NAME = "numo_prefs"
    private const val WALLET_PREFS_NAME = "cashu_wallet_prefs"

    private val stores = ConcurrentHashMap<String, ThreadSafePreferences>()

    fun app(context: Context): ThreadSafePreferences = stores.getOrPut(APP_PREFS_NAME) {
        ThreadSafePreferences(
            context.applicationContext,
            APP_PREFS_NAME,
            legacyNames = listOf("app_prefs", "NumoPrefs"),
        )
    }

    fun wallet(context: Context): ThreadSafePreferences = stores.getOrPut(WALLET_PREFS_NAME) {
        ThreadSafePreferences(context.applicationContext, WALLET_PREFS_NAME)
    }
}

class ThreadSafePreferences(
    private val context: Context,
    private val name: String,
    private val legacyNames: List<String> = emptyList(),
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val lock = Any()

    init {
        migrateLegacyPreferences()
    }

    fun getBoolean(key: String, default: Boolean = false): Boolean =
        synchronized(lock) { prefs.getBoolean(key, default) }

    fun putBoolean(key: String, value: Boolean) {
        synchronized(lock) {
            prefs.edit().putBoolean(key, value).apply()
        }
    }

    fun getString(key: String, default: String? = null): String? =
        synchronized(lock) { prefs.getString(key, default) }

    fun putString(key: String, value: String?) {
        synchronized(lock) {
            prefs.edit().putString(key, value).apply()
        }
    }

    fun remove(key: String) {
        synchronized(lock) {
            prefs.edit().remove(key).apply()
        }
    }

    private fun migrateLegacyPreferences() {
        if (legacyNames.isEmpty()) return

        legacyNames.forEach { legacyName ->
            if (legacyName == name) return@forEach
            val legacyPrefs = context.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
            if (legacyPrefs.all.isEmpty()) return@forEach

            synchronized(lock) {
                val editor = prefs.edit()
                legacyPrefs.all.forEach { (key, value) ->
                    when (value) {
                        is Boolean -> editor.putBoolean(key, value)
                        is Float -> editor.putFloat(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is String -> editor.putString(key, value)
                        is Set<*> -> editor.putStringSet(
                            key,
                            value.filterIsInstance<String>().toSet(),
                        )
                    }
                }
                editor.apply()
            }

            // Clear legacy prefs so migration is one-time.
            legacyPrefs.edit().clear().apply()
        }
    }
}
