package com.electricdreams.numo.feature.insights

import com.electricdreams.numo.core.model.Amount
import java.util.Date

enum class DisplayUnit {
    FIAT,
    SATS;

    companion object {
        fun fromKey(key: String?): DisplayUnit = when (key) {
            "sats", "btc" -> SATS
            else -> FIAT
        }
    }

    fun toKey(): String = when (this) {
        FIAT -> "fiat"
        SATS -> "sats"
    }
}

enum class InsightsRange {
    DAY,
    WEEK,
    MONTH;

    companion object {
        fun fromKey(key: String?): InsightsRange = when (key) {
            "week" -> WEEK
            "month" -> MONTH
            else -> DAY
        }
    }

    fun toKey(): String = when (this) {
        DAY -> "day"
        WEEK -> "week"
        MONTH -> "month"
    }
}

data class BucketTotal(
    val bucketIndex: Int,
    val date: Date,
    val startMillis: Long,
    val endExclusiveMillis: Long,
    val totalSats: Long,
    val totalFiatMinor: Long,
    val transactionCount: Int,
    val isCurrent: Boolean,
    val label: String,
)

data class BasketItemSummary(
    val itemName: String,
    val itemImagePath: String?,
    val quantity: Int,
)

data class BasketSummary(
    val items: List<BasketItemSummary>,
    val totalQuantity: Int,
    val distinctTypes: Int,
)

data class TxRow(
    val id: String,
    val date: Date,
    val totalSats: Long,
    val totalFiatMinor: Long,
    val basket: BasketSummary?,
)

data class InsightsData(
    val range: InsightsRange,
    val buckets: List<BucketTotal>,
    val transactions: List<TxRow>,
    val periodTotalSats: Long,
    val periodTotalFiatMinor: Long,
    val periodTxCount: Int,
    val fiatCurrency: Amount.Currency,
)
