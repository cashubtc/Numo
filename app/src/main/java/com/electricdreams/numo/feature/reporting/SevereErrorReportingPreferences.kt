package com.electricdreams.numo.feature.reporting

import android.content.Context
import androidx.core.content.edit

object SevereErrorReportingPreferences {
    private const val PREFERENCES_NAME = "IssueReportingPreferences"
    private const val KEY_ENABLED = "automaticSevereErrorReportingEnabled"

    fun isEnabled(context: Context): Boolean = context.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    ).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_ENABLED, enabled) }
        if (!enabled) {
            SevereErrorReportStore(context).clear()
            SevereErrorReportScheduler.cancel(context)
        }
    }
}
