package com.electricdreams.numo.feature.history

import android.content.SharedPreferences
import androidx.core.content.edit
import com.electricdreams.numo.core.data.model.HistoryEntry
import java.util.Calendar
import java.util.TimeZone

/** Which transactions the Activity list shows, by settlement status. */
enum class HistoryStatusFilter(val prefValue: Int) {
    ALL(0),
    PAID(1),
    PENDING(2);

    companion object {
        fun fromPref(value: Int): HistoryStatusFilter =
            values().firstOrNull { it.prefValue == value } ?: ALL
    }
}

/**
 * Date filter. Presets are stored by key, not as timestamps, so "Last 7 days" stays
 * relative to today every time the list loads.
 */
enum class HistoryDatePreset(val prefKey: String) {
    ANY_TIME("any"),
    TODAY("today"),
    LAST_7_DAYS("last_7_days"),
    LAST_30_DAYS("last_30_days"),
    THIS_MONTH("this_month"),
    CUSTOM("custom");

    companion object {
        fun fromPref(key: String?): HistoryDatePreset? = values().firstOrNull { it.prefKey == key }
    }
}

/**
 * The Activity list filter. [customStartUtc] and [customEndUtc] are the UTC-midnight day
 * values returned by MaterialDatePicker and only apply to [HistoryDatePreset.CUSTOM].
 */
data class HistoryFilter(
    val status: HistoryStatusFilter = HistoryStatusFilter.ALL,
    val datePreset: HistoryDatePreset = HistoryDatePreset.ANY_TIME,
    val customStartUtc: Long = 0L,
    val customEndUtc: Long = 0L,
) {
    val isStatusActive: Boolean get() = status != HistoryStatusFilter.ALL
    val isDateActive: Boolean get() = datePreset != HistoryDatePreset.ANY_TIME
    val activeCount: Int get() = (if (isStatusActive) 1 else 0) + (if (isDateActive) 1 else 0)

    /**
     * Inclusive millisecond range in local time, or null when any date matches. Ranges are
     * whole calendar days ending today, e.g. "Last 7 days" is today and the six days before.
     */
    fun dateRange(nowMillis: Long, timeZone: TimeZone = TimeZone.getDefault()): LongRange? {
        val endOfToday = startOfDay(nowMillis, timeZone, dayOffset = 1) - 1
        return when (datePreset) {
            HistoryDatePreset.ANY_TIME -> null
            HistoryDatePreset.TODAY -> startOfDay(nowMillis, timeZone)..endOfToday
            HistoryDatePreset.LAST_7_DAYS -> startOfDay(nowMillis, timeZone, dayOffset = -6)..endOfToday
            HistoryDatePreset.LAST_30_DAYS -> startOfDay(nowMillis, timeZone, dayOffset = -29)..endOfToday
            HistoryDatePreset.THIS_MONTH -> startOfMonth(nowMillis, timeZone)..endOfToday
            HistoryDatePreset.CUSTOM -> {
                if (customStartUtc <= 0L || customEndUtc <= 0L) return null
                val start = localStartOfUtcDay(customStartUtc, timeZone)
                val end = localStartOfUtcDay(customEndUtc, timeZone, dayOffset = 1) - 1
                start..end
            }
        }
    }

    fun matches(entry: HistoryEntry, nowMillis: Long, timeZone: TimeZone = TimeZone.getDefault()): Boolean {
        val statusMatches = when (status) {
            HistoryStatusFilter.ALL -> true
            HistoryStatusFilter.PAID -> !entry.isPending()
            HistoryStatusFilter.PENDING -> entry.isPending()
        }
        if (!statusMatches) return false
        val range = dateRange(nowMillis, timeZone) ?: return true
        return entry.date.time in range
    }

    fun withoutStatus(): HistoryFilter = copy(status = HistoryStatusFilter.ALL)

    fun withoutDate(): HistoryFilter =
        copy(datePreset = HistoryDatePreset.ANY_TIME, customStartUtc = 0L, customEndUtc = 0L)

    companion object {
        // Keys shared with PaymentsHistoryActivity's "PaymentHistory" preferences
        const val KEY_STATUS = "filter_state"
        const val KEY_DATE_PRESET = "filter_date_preset"
        const val KEY_DATE_START = "filter_date_start"
        const val KEY_DATE_END = "filter_date_end"

        fun load(prefs: SharedPreferences): HistoryFilter {
            val start = prefs.getLong(KEY_DATE_START, 0L)
            val end = prefs.getLong(KEY_DATE_END, 0L)
            // Before presets existed only a custom range was stored.
            val preset = HistoryDatePreset.fromPref(prefs.getString(KEY_DATE_PRESET, null))
                ?: if (start > 0L && end > 0L) HistoryDatePreset.CUSTOM else HistoryDatePreset.ANY_TIME
            return HistoryFilter(
                status = HistoryStatusFilter.fromPref(prefs.getInt(KEY_STATUS, HistoryStatusFilter.ALL.prefValue)),
                datePreset = preset,
                customStartUtc = if (preset == HistoryDatePreset.CUSTOM) start else 0L,
                customEndUtc = if (preset == HistoryDatePreset.CUSTOM) end else 0L,
            )
        }

        fun save(prefs: SharedPreferences, filter: HistoryFilter) {
            prefs.edit {
                putInt(KEY_STATUS, filter.status.prefValue)
                putString(KEY_DATE_PRESET, filter.datePreset.prefKey)
                putLong(KEY_DATE_START, filter.customStartUtc)
                putLong(KEY_DATE_END, filter.customEndUtc)
            }
        }

        private fun startOfDay(millis: Long, timeZone: TimeZone, dayOffset: Int = 0): Long =
            Calendar.getInstance(timeZone).apply {
                timeInMillis = millis
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.DAY_OF_MONTH, dayOffset)
            }.timeInMillis

        private fun startOfMonth(millis: Long, timeZone: TimeZone): Long =
            Calendar.getInstance(timeZone).apply {
                timeInMillis = startOfDay(millis, timeZone)
                set(Calendar.DAY_OF_MONTH, 1)
            }.timeInMillis

        /** Local midnight of the calendar day a UTC-midnight picker value stands for. */
        private fun localStartOfUtcDay(utcMillis: Long, timeZone: TimeZone, dayOffset: Int = 0): Long {
            val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMillis }
            return Calendar.getInstance(timeZone).apply {
                clear()
                set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH))
                add(Calendar.DAY_OF_MONTH, dayOffset)
            }.timeInMillis
        }
    }
}
