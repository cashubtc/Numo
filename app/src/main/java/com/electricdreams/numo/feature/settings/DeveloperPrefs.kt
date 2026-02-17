package com.electricdreams.numo.feature.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages developer settings preferences.
 * Developer mode is enabled by tapping the version number 5 times in About.
 */
object DeveloperPrefs {
    private const val PREFS_NAME = "developer_prefs"
    private const val KEY_DEVELOPER_MODE_ENABLED = "developer_mode_enabled"
    private const val KEY_DELAY_LIGHTNING_INVOICE = "delay_lightning_invoice"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isDeveloperModeEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_DEVELOPER_MODE_ENABLED, false)
    }

    fun setDeveloperModeEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_DEVELOPER_MODE_ENABLED, enabled).apply()
    }

    fun isLightningInvoiceDelayed(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_DELAY_LIGHTNING_INVOICE, false)
    }

    fun setLightningInvoiceDelayed(context: Context, delayed: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_DELAY_LIGHTNING_INVOICE, delayed).apply()
    }
}
