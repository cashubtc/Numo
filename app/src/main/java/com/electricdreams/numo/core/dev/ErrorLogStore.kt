package com.electricdreams.numo.core.dev

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.electricdreams.numo.AppGlobals
import com.electricdreams.numo.core.data.model.ErrorLogEntry
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** Bounded persistent error history. Call reads and writes from Dispatchers.IO. */
object ErrorLogStore {
    private const val TAG = "ErrorLogStore"
    private const val PREFS_NAME = "DeveloperErrorLogs"
    private const val KEY_LOGS = "logs"
    private const val MAX_ENTRIES = 500

    private val legacyDateAdapter = Gson().getAdapter(Date::class.java)
    private val gson = GsonBuilder().registerTypeAdapter(Date::class.java, object : TypeAdapter<Date>() {
        override fun write(writer: JsonWriter, value: Date?) {
            if (value == null) writer.nullValue() else writer.value(value.time)
        }

        override fun read(reader: JsonReader): Date? = when (reader.peek()) {
            JsonToken.NUMBER -> Date(reader.nextLong())
            JsonToken.NULL -> { reader.nextNull(); null }
            else -> legacyDateAdapter.read(reader) // Preserve logs saved by older app versions.
        }
    }).create()

    private fun preferences(): SharedPreferences = AppGlobals.getAppContext()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun appendError(
        tag: String,
        message: String,
        throwable: Throwable? = null,
        timestamp: Date = Date(),
    ) {
        record(ErrorLogEntry(
            id = UUID.randomUUID().toString(),
            timestamp = timestamp,
            tag = tag,
            message = message,
            stackTrace = ErrorLogSummary.stackTop(throwable?.stackTraceToString()),
        ))
    }

    /** Update a split exception or ignore a record replayed after a collector restart. */
    @Synchronized
    internal fun record(entry: ErrorLogEntry) {
        val entries = loadAllInternal().toMutableList()
        val bounded = entry.copy(stackTrace = ErrorLogSummary.stackTop(entry.stackTrace))
        val existing = entries.indexOfFirst { it.id == bounded.id }
        if (existing >= 0) {
            if (entries[existing] == bounded) return
            entries[existing] = bounded
        } else {
            entries.add(bounded)
        }
        saveAll(entries.sortedBy { it.timestamp.time }.takeLast(MAX_ENTRIES))
    }

    @Synchronized
    fun getErrorsUpTo(endInclusive: Date): List<ErrorLogEntry> =
        getAllErrors().filter { it.timestamp.time <= endInclusive.time }

    @Synchronized
    fun getAllErrors(): List<ErrorLogEntry> = loadAllInternal().sortedBy { it.timestamp.time }

    /** Register before the initial read so an error arriving as the screen opens isn't missed. */
    fun observeErrors(): Flow<List<ErrorLogEntry>> = callbackFlow {
        val prefs = preferences()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_LOGS || key == null) trySend(Unit)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate().map { getAllErrors() }.flowOn(Dispatchers.IO)

    @Synchronized
    fun clearAll() = saveAll(emptyList())

    private fun loadAllInternal(): List<ErrorLogEntry> {
        val array = try {
            val json = preferences().getString(KEY_LOGS, "[]") ?: "[]"
            val parsed = JsonParser.parseString(json)
            if (!parsed.isJsonArray) throw JsonParseException("Expected an error log array")
            parsed.asJsonArray
        } catch (e: RuntimeException) {
            Log.w(TAG, "Discarding unreadable error history", e)
            saveAll(emptyList())
            return emptyList()
        }

        val entries = array.mapNotNull { value ->
            try {
                val obj = value.asJsonObject
                for (field in listOf("id", "timestamp", "tag", "message")) {
                    if (!obj.has(field) || obj[field].isJsonNull) {
                        throw JsonParseException("Error log is missing $field")
                    }
                }
                gson.fromJson(obj, ErrorLogEntry::class.java).let {
                    it.copy(stackTrace = ErrorLogSummary.stackTop(it.stackTrace))
                }
            } catch (e: RuntimeException) {
                Log.w(TAG, "Discarding a malformed error log entry", e)
                null
            }
        }
        if (entries.size != array.size()) saveAll(entries)
        return entries
    }

    private fun saveAll(entries: List<ErrorLogEntry>) {
        preferences().edit().putString(KEY_LOGS, gson.toJson(entries)).apply()
    }
}
