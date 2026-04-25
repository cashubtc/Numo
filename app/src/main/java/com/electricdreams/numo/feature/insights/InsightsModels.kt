package com.electricdreams.numo.feature.insights

import com.electricdreams.numo.core.model.Amount
import java.util.Date

enum class DisplayUnit {
    FIAT,
    SATS,
    BTC;

    companion object {
        fun fromKey(key: String?): DisplayUnit = when (key) {
            "sats" -> SATS
            "btc" -> BTC
            else -> FIAT
        }
    }

    fun toKey(): String = when (this) {
        FIAT -> "fiat"
        SATS -> "sats"
        BTC -> "btc"
    }
}

data class DailyTotal(
    val dayIndex: Int,
    val date: Date,
    val dayStartMillis: Long,
    val dayEndExclusiveMillis: Long,
    val totalSats: Long,
    val totalFiatMinor: Long,
    val transactionCount: Int,
    val isToday: Boolean,
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
    val perDay: List<DailyTotal>,
    val transactions: List<TxRow>,
    val periodTotalSats: Long,
    val periodTotalFiatMinor: Long,
    val periodTxCount: Int,
    val avgDailySats: Long,
    val avgDailyFiatMinor: Long,
    val fiatCurrency: Amount.Currency,
)
