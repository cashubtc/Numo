/**
 * Persistent storage for developer error logs.
 *
 * This store keeps a bounded list of [ErrorLogEntry] objects in SharedPreferences,
 * allowing the Developer Settings > Error Logs screen to display recent
 * application errors without relying on external logcat access.
 */
package com.electricdreams.numo.core.dev

import android.content.Context
import com.electricdreams.numo.AppGlobals
import com.electricdreams.numo.core.data.model.ErrorLogEntry
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Date
import java.util.UUID

object ErrorLogStore {

    private const val PREFS_NAME = "DeveloperErrorLogs"
    private const val KEY_LOGS = "logs"
    private const val MAX_ENTRIES = 500

    private val gson = Gson()

    private val context: Context
        get() = AppGlobals.getAppContext()

    /**
     * Append a new error entry to the store.
     * Oldest entries are discarded when [MAX_ENTRIES] is exceeded.
     */
    @Synchronized
    fun appendError(tag: String, message: String, throwable: Throwable? = null) {
        val entries = loadAllInternal().toMutableList()

        val stack = throwable?.stackTraceToString()

        entries.add(
            ErrorLogEntry(
                id = UUID.randomUUID().toString(),
                timestamp = Date(),
                tag = tag,
                message = message,
                stackTrace = stack,
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
     * Returns all error entries whose timestamp is less than or equal to [endInclusive].
     * Entries are sorted by timestamp ascending.
     */
    fun getErrorsUpTo(endInclusive: Date): List<ErrorLogEntry> {
        return loadAllInternal()
            .filter { it.timestamp.time <= endInclusive.time }
            .sortedBy { it.timestamp.time }
    }

    /**
     * Returns all stored error entries sorted by timestamp ascending.
     */
    fun getAllErrors(): List<ErrorLogEntry> =
        loadAllInternal().sortedBy { it.timestamp.time }

    /**
     * Remove all stored error entries.
     */
    @Synchronized
    fun clearAll() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LOGS, "[]").apply()
    }

    private fun loadAllInternal(): List<ErrorLogEntry> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_LOGS, "[]")
        val type = object : TypeToken<ArrayList<ErrorLogEntry>>() {}.type
        return gson.fromJson(json, type) ?: emptyList()
    }

    private fun saveAll(entries: List<ErrorLogEntry>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = gson.toJson(entries)
        prefs.edit().putString(KEY_LOGS, json).apply()
    }
}
