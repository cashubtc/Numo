package com.electricdreams.numo.feature.insights

import android.content.Context
import com.electricdreams.numo.core.data.model.PaymentHistoryEntry
import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.core.util.SavedBasketManager
import com.electricdreams.numo.feature.history.PaymentsHistoryActivity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object InsightsRepository {

    fun compute(context: Context, range: InsightsRange): InsightsData {
        val currentCurrencyCode = com.electricdreams.numo.core.util.MintManager.getActiveCurrencyCode(context)
        val fiatCurrency = Amount.Currency.fromCode(currentCurrencyCode)
        val currentBtcPrice = com.electricdreams.numo.core.worker.BitcoinPriceWorker.getInstance(context).getCurrentPrice()
        // "sat", or a mint's custom unit (e.g. "usd") that replaces sats entirely. Not the same
        // as currentCurrencyCode, which is the fiat display currency while in sats.
        val baseUnit = com.electricdreams.numo.core.util.MintManager.getInstance(context).getPreferredUnit()
        val isSatsMode = baseUnit.equals("sat", ignoreCase = true)

        val locale = Locale.getDefault()
        val buckets = buildBuckets(range, locale)
        val periodStart = buckets.first().startMillis
        val periodEnd = buckets.last().endExclusiveMillis

        val payments = PaymentsHistoryActivity.getPaymentHistory(context)
            .filter { it.isCompleted() }
            .filter { it.date.time in periodStart until periodEnd }
            .filter { isInBaseUnit(it, baseUnit) }
            .sortedByDescending { it.date.time }

        val basketManager = SavedBasketManager.getInstance(context)
        val imagesByItemId = SaleSummaries.imagesByItemId(context)

        val perBucket = Array(7) { SaleValue(0, 0, 0, 0) }
        val perBucketTxCount = IntArray(7)

        val values = payments.map { entry ->
            val value = if (isSatsMode) {
                valueAtSale(entry, currentCurrencyCode, currentBtcPrice)
            } else {
                // A mint's own unit: amounts are already in its minor units, with no sats behind them
                SaleValue(entry.enteredAmount, entry.enteredAmount, entry.tipAmountSats, entry.tipAmountSats)
            }
            val idx = buckets.indexOfBucket(entry.date.time)
            perBucketTxCount[idx] += 1
            perBucket[idx] = perBucket[idx] + value
            entry to value
        }
        val period = values.fold(SaleValue(0, 0, 0, 0)) { sum, (_, value) -> sum + value }

        val txRows = values.map { (entry, value) ->
            TxRow(
                id = entry.id,
                date = entry.date,
                totalSats = value.sats,
                totalFiatMinor = value.fiatMinor,
                basket = SaleSummaries.basket(entry, basketManager, imagesByItemId),
            )
        }

        val filledBuckets = buckets.mapIndexed { idx, scaffold ->
            scaffold.copy(
                totalSats = perBucket[idx].sats,
                totalFiatMinor = perBucket[idx].fiatMinor,
                tipSats = perBucket[idx].tipSats,
                tipFiatMinor = perBucket[idx].tipFiatMinor,
                transactionCount = perBucketTxCount[idx],
            )
        }

        return InsightsData(
            range = range,
            buckets = filledBuckets,
            transactions = txRows,
            periodTotalSats = period.sats,
            periodTotalFiatMinor = period.fiatMinor,
            periodTipSats = period.tipSats,
            periodTipFiatMinor = period.tipFiatMinor,
            periodTxCount = txRows.size,
            fiatCurrency = fiatCurrency,
        )
    }

    /**
     * A sats sale, in [fiatCode], as of when it was paid: what was charged before tip, and the
     * tip on its own. Past sales keep their value; today's bitcoin price is only the fallback
     * when the sale didn't record one it can use.
     */
    internal fun valueAtSale(
        entry: PaymentHistoryEntry,
        fiatCode: String,
        currentBtcPrice: Double?,
    ): SaleValue {
        val saleSats = entry.getBaseAmountSats()
        val tipSats = entry.tipAmountSats
        val entryUnit = entry.getEntryUnit()
        val keyedInThisFiat = entry.enteredAmount > 0 && entryUnit.equals(fiatCode, ignoreCase = true)
        // A sale keyed in sats recorded the price in the display currency of the day; one keyed
        // in another fiat recorded that currency's price, which can't value it in this one
        val priceAtSale = entry.bitcoinPrice?.takeIf {
            it > 0 && (keyedInThisFiat || entryUnit.equals("sat", ignoreCase = true))
        }
        val price = priceAtSale ?: currentBtcPrice
        return SaleValue(
            sats = saleSats,
            fiatMinor = if (keyedInThisFiat) entry.enteredAmount else satsToFiatMinor(saleSats, price),
            tipSats = tipSats,
            tipFiatMinor = satsToFiatMinor(tipSats, price),
        )
    }

    private operator fun SaleValue.plus(other: SaleValue) = SaleValue(
        sats = sats + other.sats,
        fiatMinor = fiatMinor + other.fiatMinor,
        tipSats = tipSats + other.tipSats,
        tipFiatMinor = tipFiatMinor + other.tipFiatMinor,
    )

    /**
     * Whether [entry] was received in [baseUnit]. This is the ecash unit, not the currency
     * the amount was typed in: a sale keyed in dollars is still paid in sats.
     */
    internal fun isInBaseUnit(entry: PaymentHistoryEntry, baseUnit: String): Boolean {
        val unit = entry.getUnit().lowercase()
        val base = baseUnit.lowercase()
        // Backward compatibility fallback for legacy satoshi entries recorded as "btc" or "sats"
        return unit == base || (base == "sat" && (unit == "btc" || unit == "sats"))
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
