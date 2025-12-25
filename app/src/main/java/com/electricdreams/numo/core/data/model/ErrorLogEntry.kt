/**
 * Data model representing a single error log entry captured by the app.
 */
package com.electricdreams.numo.core.data.model

import java.util.Date

/**
 * Represents a single error log line, including optional stack trace.
 */
data class ErrorLogEntry(
    /** Unique identifier for this log entry (for stable list handling). */
    val id: String,
    /** Time when the error occurred. */
    val timestamp: Date,
    /** Log tag used when the error was emitted. */
    val tag: String,
    /** Human-readable error message. */
    val message: String,
    /** Optional stack trace string, if a throwable was provided. */
    val stackTrace: String? = null,
)
