package com.electricdreams.numo.feature.insights

import android.content.Context
import com.electricdreams.numo.core.data.model.PaymentHistoryEntry
import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.core.util.CurrencyManager
import com.electricdreams.numo.core.util.SavedBasketManager
import com.electricdreams.numo.feature.history.PaymentsHistoryActivity
import java.util.Calendar
import java.util.Date

object InsightsRepository {

    fun computeLast7Days(context: Context): InsightsData {
        val fiatCurrency = Amount.Currency.fromCode(
            CurrencyManager.getInstance(context).getCurrentCurrency()
        )

        val days = buildLast7Days()
        val periodStart = days.first().dayStartMillis
        val periodEnd = days.last().dayEndExclusiveMillis

        val payments = PaymentsHistoryActivity.getPaymentHistory(context)
            .filter { it.isCompleted() }
            .filter { it.date.time in periodStart until periodEnd }
            .sortedByDescending { it.date.time }

        val basketManager = SavedBasketManager.getInstance(context)

        val perDayCounts = LongArray(7)
        val perDayFiat = LongArray(7)
        val perDayTxCount = IntArray(7)

        var periodTotalSats = 0L
        var periodTotalFiatMinor = 0L

        val txRows = payments.map { entry ->
            val (dayIdx, _) = days.indexOfDay(entry.date.time)
            perDayCounts[dayIdx] += entry.amount
            perDayTxCount[dayIdx] += 1
            periodTotalSats += entry.amount

            val fiatMinor = satsToFiatMinor(entry.amount, entry.bitcoinPrice)
            perDayFiat[dayIdx] += fiatMinor
            periodTotalFiatMinor += fiatMinor

            val basket = entry.basketId?.let { basketManager.getBasket(it) }
            val basketSummary = basket?.takeIf { it.items.isNotEmpty() }?.let { b ->
                val items = b.items
                    .filter { it.item.name?.isNotBlank() == true }
                    .groupBy { it.item.name!! }
                    .map { (name, instances) ->
                        BasketItemSummary(
                            itemName = name,
                            itemImagePath = instances.firstNotNullOfOrNull { it.item.imagePath?.takeIf { p -> p.isNotBlank() } },
                            quantity = instances.sumOf { it.quantity },
                        )
                    }
                    .sortedByDescending { it.quantity }
                BasketSummary(
                    items = items,
                    totalQuantity = items.sumOf { it.quantity },
                    distinctTypes = items.size,
                )
            }

            TxRow(
                id = entry.id,
                date = entry.date,
                totalSats = entry.amount,
                totalFiatMinor = fiatMinor,
                basket = basketSummary,
            )
        }

        val perDay = days.mapIndexed { idx, scaffold ->
            scaffold.copy(
                totalSats = perDayCounts[idx],
                totalFiatMinor = perDayFiat[idx],
                transactionCount = perDayTxCount[idx],
            )
        }

        val avgDailySats = perDay.sumOf { it.totalSats } / 7
        val avgDailyFiatMinor = perDay.sumOf { it.totalFiatMinor } / 7

        return InsightsData(
            perDay = perDay,
            transactions = txRows,
            periodTotalSats = periodTotalSats,
            periodTotalFiatMinor = periodTotalFiatMinor,
            periodTxCount = txRows.size,
            avgDailySats = avgDailySats,
            avgDailyFiatMinor = avgDailyFiatMinor,
            fiatCurrency = fiatCurrency,
        )
    }

    private fun buildLast7Days(): List<DailyTotal> {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val todayStart = cal.timeInMillis
        cal.add(Calendar.DAY_OF_MONTH, -6)
        return (0..6).map { i ->
            val dayStart = cal.timeInMillis
            cal.add(Calendar.DAY_OF_MONTH, 1)
            val dayEnd = cal.timeInMillis
            DailyTotal(
                dayIndex = i,
                date = Date(dayStart),
                dayStartMillis = dayStart,
                dayEndExclusiveMillis = dayEnd,
                totalSats = 0,
                totalFiatMinor = 0,
                transactionCount = 0,
                isToday = dayStart == todayStart,
            )
        }
    }

    private fun List<DailyTotal>.indexOfDay(timeMillis: Long): Pair<Int, DailyTotal> {
        for ((idx, day) in withIndex()) {
            if (timeMillis in day.dayStartMillis until day.dayEndExclusiveMillis) return idx to day
        }
        return (size - 1) to last()
    }

    private fun satsToFiatMinor(sats: Long, btcPrice: Double?): Long {
        if (sats <= 0 || btcPrice == null || btcPrice <= 0) return 0
        return kotlin.math.round(sats.toDouble() / 100_000_000.0 * btcPrice * 100.0).toLong()
    }
}
