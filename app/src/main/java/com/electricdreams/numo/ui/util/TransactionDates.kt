package com.electricdreams.numo.ui.util

import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dates for the payment screens (Activity, Sales, transaction details), in the device's locale
 * and 12/24-hour setting so the three read the same.
 */
object TransactionDates {

    /** A list row: "Sep 27 · 10:26", or "Sep 27 · 10:26 AM" on a 12-hour phone */
    fun row(context: Context, date: Date): String {
        val locale = Locale.getDefault()
        val day = SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "MMMd"), locale)
        return "${day.format(date)} · ${DateFormat.getTimeFormat(context).format(date)}"
    }

    /** A details headline: "September 27, 2026, 10:26 AM" */
    fun full(context: Context, date: Date): String = DateUtils.formatDateTime(
        context,
        date.time,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_TIME,
    )
}
