package com.electricdreams.numo

import android.app.Application
import android.util.Log
import com.electricdreams.numo.core.dev.ErrorLogCollector

/**
 * Custom Application class for global initialisation.
 */
class NumoApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Expose application context for components without direct Android context (e.g., Nostr listeners)
        AppGlobals.init(this)
        // Wallet initialisation is handled by onboarding / ModernPOS flows.
        Log.d("NumoApplication", "Application initialised")

        // Start developer error log collection in debug builds so the
        // Developer Settings > Error Logs screen can show recent errors
        // without modifying existing Log.e() sites.
        if (BuildConfig.DEBUG) {
            ErrorLogCollector.start()
        }
    }
}
