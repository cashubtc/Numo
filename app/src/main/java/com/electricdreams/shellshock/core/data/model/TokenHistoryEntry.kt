package com.electricdreams.shellshock.core.data.model

import com.google.gson.annotations.SerializedName
import java.util.Date

/**
 * Represents a token history entry.
 */
data class TokenHistoryEntry(
    @SerializedName("token")
    val token: String,

    @SerializedName("amount")
    val amount: Long,

    @SerializedName("date")
    val date: Date,
)
