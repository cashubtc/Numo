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

    fun contains(key: String): Boolean = synchronized(lock) { prefs.contains(key) }

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

    /** Reads through this store and applies related changes under the same lock. */
    fun <T> edit(update: SharedPreferences.Editor.() -> T): T = synchronized(lock) {
        val editor = prefs.edit()
        val result = editor.update()
        editor.apply()
        result
    }

    fun commitStrings(values: Map<String, String?>): Boolean = commitValues(values, emptyMap())

    fun commitValues(
        values: Map<String, String?>,
        booleans: Map<String, Boolean>,
    ): Boolean = synchronized(lock) {
        val oldBooleans = booleans.keys.associateWith { key ->
            if (prefs.contains(key)) prefs.getBoolean(key, false) else null
        }
        val previous = values.keys.associateWith { prefs.getString(it, null) }
        val editor = prefs.edit()
        values.forEach { (key, value) -> editor.putString(key, value) }
        booleans.forEach { (key, value) -> editor.putBoolean(key, value) }
        if (editor.commit()) {
            true
        } else {
            val rollback = prefs.edit()
            previous.forEach { (key, value) -> rollback.putString(key, value) }
            oldBooleans.forEach { (key, value) ->
                if (value == null) rollback.remove(key) else rollback.putBoolean(key, value)
            }
            rollback.commit()
            false
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
