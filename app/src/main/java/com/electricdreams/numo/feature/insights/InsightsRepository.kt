package com.electricdreams.numo.feature.insights

import android.content.Context
import com.electricdreams.numo.core.data.model.PaymentHistoryEntry
import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.core.util.CurrencyManager
import com.electricdreams.numo.core.util.ItemManager
import com.electricdreams.numo.core.util.SavedBasketManager
import com.electricdreams.numo.feature.history.PaymentsHistoryActivity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object InsightsRepository {

    fun compute(context: Context, range: InsightsRange): InsightsData {
        val fiatCurrency = Amount.Currency.fromCode(
            CurrencyManager.getInstance(context).getCurrentCurrency()
        )

        val locale = Locale.getDefault()
        val buckets = buildBuckets(range, locale)
        val periodStart = buckets.first().startMillis
        val periodEnd = buckets.last().endExclusiveMillis

        val payments = PaymentsHistoryActivity.getPaymentHistory(context)
            .filter { it.isCompleted() }
            .filter { it.date.time in periodStart until periodEnd }
            .sortedByDescending { it.date.time }

        val basketManager = SavedBasketManager.getInstance(context)
        val imagesByItemId = ItemManager.getInstance(context).getAllItems()
            .mapNotNull { item ->
                val id = item.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val path = item.imagePath?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                id to path
            }
            .toMap()

        val perBucketSats = LongArray(7)
        val perBucketFiat = LongArray(7)
        val perBucketTxCount = IntArray(7)

        var periodTotalSats = 0L
        var periodTotalFiatMinor = 0L

        val txRows = payments.map { entry ->
            val idx = buckets.indexOfBucket(entry.date.time)
            perBucketSats[idx] += entry.amount
            perBucketTxCount[idx] += 1
            periodTotalSats += entry.amount

            val fiatMinor = satsToFiatMinor(entry.amount, entry.bitcoinPrice)
            perBucketFiat[idx] += fiatMinor
            periodTotalFiatMinor += fiatMinor

            TxRow(
                id = entry.id,
                date = entry.date,
                totalSats = entry.amount,
                totalFiatMinor = fiatMinor,
                basket = buildBasketSummary(entry, basketManager, imagesByItemId),
            )
        }

        val filledBuckets = buckets.mapIndexed { idx, scaffold ->
            scaffold.copy(
                totalSats = perBucketSats[idx],
                totalFiatMinor = perBucketFiat[idx],
                transactionCount = perBucketTxCount[idx],
            )
        }

        return InsightsData(
            range = range,
            buckets = filledBuckets,
            transactions = txRows,
            periodTotalSats = periodTotalSats,
            periodTotalFiatMinor = periodTotalFiatMinor,
            periodTxCount = txRows.size,
            fiatCurrency = fiatCurrency,
        )
    }

    private fun buildBuckets(range: InsightsRange, locale: Locale): List<BucketTotal> {
        val dayLabelFmt = SimpleDateFormat("EEE", locale)
        val weekLabelFmt = SimpleDateFormat("MMM d", locale)
        val monthLabelFmt = SimpleDateFormat("MMM", locale)

        val cursor = Calendar.getInstance(locale).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val nowStartOfDay = cursor.timeInMillis

        when (range) {
            InsightsRange.DAY -> cursor.add(Calendar.DAY_OF_MONTH, -6)
            InsightsRange.WEEK -> {
                val first = cursor.firstDayOfWeek
                while (cursor.get(Calendar.DAY_OF_WEEK) != first) {
                    cursor.add(Calendar.DAY_OF_MONTH, -1)
                }
                cursor.add(Calendar.WEEK_OF_YEAR, -6)
            }
            InsightsRange.MONTH -> {
                cursor.set(Calendar.DAY_OF_MONTH, 1)
                cursor.add(Calendar.MONTH, -6)
            }
        }

        return (0..6).map { i ->
            val start = cursor.timeInMillis
            val startDate = Date(start)
            when (range) {
                InsightsRange.DAY -> cursor.add(Calendar.DAY_OF_MONTH, 1)
                InsightsRange.WEEK -> cursor.add(Calendar.WEEK_OF_YEAR, 1)
                InsightsRange.MONTH -> cursor.add(Calendar.MONTH, 1)
            }
            val end = cursor.timeInMillis
            val label = when (range) {
                InsightsRange.DAY -> dayLabelFmt.format(startDate)
                InsightsRange.WEEK -> weekLabelFmt.format(startDate)
                InsightsRange.MONTH -> monthLabelFmt.format(startDate)
            }
            BucketTotal(
                bucketIndex = i,
                date = startDate,
                startMillis = start,
                endExclusiveMillis = end,
                totalSats = 0,
                totalFiatMinor = 0,
                transactionCount = 0,
                isCurrent = nowStartOfDay in start until end,
                label = label,
            )
        }
    }

    private fun buildBasketSummary(
        entry: PaymentHistoryEntry,
        basketManager: SavedBasketManager,
        imagesByItemId: Map<String, String>,
    ): BasketSummary? {
        val saved = entry.basketId?.let { basketManager.getBasket(it) }
        if (saved != null && saved.items.isNotEmpty()) {
            val items = saved.items
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
            return BasketSummary(
                items = items,
                totalQuantity = items.sumOf { it.quantity },
                distinctTypes = items.size,
            )
        }

        val checkout = entry.getCheckoutBasket()
        if (checkout != null && checkout.items.isNotEmpty()) {
            val items = checkout.items
                .filter { it.name.isNotBlank() }
                .groupBy { it.name }
                .map { (name, instances) ->
                    BasketItemSummary(
                        itemName = name,
                        itemImagePath = instances.firstNotNullOfOrNull { imagesByItemId[it.itemId] },
                        quantity = instances.sumOf { it.quantity },
                    )
                }
                .sortedByDescending { it.quantity }
            return BasketSummary(
                items = items,
                totalQuantity = items.sumOf { it.quantity },
                distinctTypes = items.size,
            )
        }

        return null
    }

    private fun List<BucketTotal>.indexOfBucket(timeMillis: Long): Int {
        for ((idx, bucket) in withIndex()) {
            if (timeMillis in bucket.startMillis until bucket.endExclusiveMillis) return idx
        }
        return size - 1
    }

    private fun satsToFiatMinor(sats: Long, btcPrice: Double?): Long {
        if (sats <= 0 || btcPrice == null || btcPrice <= 0) return 0
        return kotlin.math.round(sats.toDouble() / 100_000_000.0 * btcPrice * 100.0).toLong()
    }
}
