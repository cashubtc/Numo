package com.electricdreams.numo

import android.app.Application
import android.content.Context

/**
 * Application-wide access to the app context for components that do not have
 * an Android Context (e.g., pure Java Nostr listeners).
 *
 * This should be initialised from the custom Application class's onCreate.
 */
object AppGlobals {

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun getAppContext(): Context {
        return appContext
            ?: throw IllegalStateException("AppGlobals not initialised. Call AppGlobals.init from Application.onCreate().")
    }
}
