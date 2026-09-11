/**
 * Developer logging helper.
 *
 * Errors are persisted by [ErrorLogCollector] when Developer Mode is enabled.
 * Use the same collection path as Log.e to avoid recording each error twice.
 */
package com.electricdreams.numo.core.dev

import android.util.Log

object DevLogger {

    /**
     * Log an error message for logcat and the developer error collector.
     */
    @JvmStatic
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.e(tag, message)
        }
    }

    /**
     * Convenience overload matching [Log.e] that does not take a throwable.
     */
    @JvmStatic
    fun e(tag: String, message: String) {
        e(tag, message, null)
    }
}
