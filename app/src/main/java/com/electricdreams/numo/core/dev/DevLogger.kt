/**
 * Developer logging helper.
 *
 * This wrapper mirrors [android.util.Log] for error-level logging while
 * additionally persisting error information to [ErrorLogStore] so it can
 * be inspected from the in-app Developer Settings.
 */
package com.electricdreams.numo.core.dev

import android.util.Log

object DevLogger {

    /**
     * Log an error message and persist it to [ErrorLogStore].
     */
    @JvmStatic
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.e(tag, message)
        }

        ErrorLogStore.appendError(tag = tag, message = message, throwable = throwable)
    }

    /**
     * Convenience overload matching [Log.e] that does not take a throwable.
     */
    @JvmStatic
    fun e(tag: String, message: String) {
        e(tag, message, null as Throwable?)
    }
}
