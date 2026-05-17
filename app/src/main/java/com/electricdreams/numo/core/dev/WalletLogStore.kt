/**
 * Persistent storage for wallet activity logs.
 *
 * This store keeps a bounded list of [WalletLogEntry] objects in SharedPreferences,
 * allowing the Developer Settings > Wallet Logs screen to display recent
 * wallet activity.
 */
package com.electricdreams.numo.core.dev

import android.content.Context
import com.electricdreams.numo.AppGlobals
import com.electricdreams.numo.core.data.model.WalletLogEntry
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Date
import java.util.UUID

object WalletLogStore {

    private const val PREFS_NAME = "WalletActivityLogs"
    private const val KEY_LOGS = "logs"
    private const val MAX_ENTRIES = 500

    private val gson = Gson()

    private val context: Context
        get() = AppGlobals.getAppContext()

    /**
     * Append a new wallet activity entry to the store.
     * Oldest entries are discarded when [MAX_ENTRIES] is exceeded.
     */
    @Synchronized
    fun appendEntry(direction: String, amount: Long, mintUrl: String, message: String) {
        val entries = loadAllInternal().toMutableList()

        entries.add(
            WalletLogEntry(
                id = UUID.randomUUID().toString(),
                timestamp = Date(),
                direction = direction,
                amount = amount,
                mintUrl = mintUrl,
                message = message,
            ),
        )

        val trimmedEntries = if (entries.size > MAX_ENTRIES) {
            entries.takeLast(MAX_ENTRIES)
        } else {
            entries
        }

        saveAll(trimmedEntries)
    }

    /**
     * Returns all stored wallet log entries sorted by timestamp descending.
     */
    fun getAllEntries(): List<WalletLogEntry> =
        loadAllInternal().sortedByDescending { it.timestamp.time }

    /**
     * Remove all stored wallet log entries.
     */
    @Synchronized
    fun clearAll() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LOGS, "[]").apply()
    }

    private fun loadAllInternal(): List<WalletLogEntry> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_LOGS, "[]")
        val type = object : TypeToken<ArrayList<WalletLogEntry>>() {}.type
        return gson.fromJson(json, type) ?: emptyList()
    }

    private fun saveAll(entries: List<WalletLogEntry>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = gson.toJson(entries)
        prefs.edit().putString(KEY_LOGS, json).apply()
    }
}
