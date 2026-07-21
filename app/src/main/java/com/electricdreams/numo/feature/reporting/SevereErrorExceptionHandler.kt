package com.electricdreams.numo.feature.reporting

import android.content.Context

class SevereErrorExceptionHandler(
    private val context: Context,
    private val delegate: Thread.UncaughtExceptionHandler?,
    private val store: SevereErrorReportStore = SevereErrorReportStore(context)
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            if (SevereErrorReportingPreferences.isEnabled(context)) {
                store.write(SevereErrorSanitizer.sanitize(throwable))
            }
        } catch (_: Throwable) {
            // A crash handler must never interfere with the platform's normal crash path.
        } finally {
            delegate?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        fun install(context: Context) {
            val current = Thread.getDefaultUncaughtExceptionHandler()
            if (current is SevereErrorExceptionHandler) return
            Thread.setDefaultUncaughtExceptionHandler(
                SevereErrorExceptionHandler(context.applicationContext, current)
            )
        }
    }
}
