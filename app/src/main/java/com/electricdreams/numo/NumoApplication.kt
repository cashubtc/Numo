package com.electricdreams.numo

import android.app.Application
import android.util.Log
import com.electricdreams.numo.core.dev.ErrorLogCollector
import com.electricdreams.numo.feature.settings.DeveloperPrefs

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

        // Developer Mode enables diagnostics in installed release builds too.
        if (DeveloperPrefs.isDeveloperModeEnabled(this)) {
            ErrorLogCollector.start()
        }
    }
}
