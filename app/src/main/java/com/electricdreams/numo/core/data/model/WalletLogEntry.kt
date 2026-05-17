/**
 * Data model representing a single wallet activity log entry captured by the app.
 */
package com.electricdreams.numo.core.data.model

import java.util.Date

/**
 * Represents a single wallet activity log line.
 */
data class WalletLogEntry(
    /** Unique identifier for this log entry. */
    val id: String,
    /** Time when the activity occurred. */
    val timestamp: Date,
    /** Direction of money movement: "IN" or "OUT". */
    val direction: String,
    /** Amount in sats. */
    val amount: Long,
    /** Mint URL involved in the transaction. */
    val mintUrl: String,
    /** Human-readable message describing the activity. */
    val message: String,
)
