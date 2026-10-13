package com.electricdreams.numo.feature.insights

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.electricdreams.numo.core.data.model.PaymentHistoryEntry
import com.electricdreams.numo.core.util.MintManager
import com.electricdreams.numo.core.worker.BitcoinPriceWorker
import com.google.gson.Gson
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Date

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InsightsRepositoryTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        BitcoinPriceWorker.isTesting = true
        context = ApplicationProvider.getApplicationContext()
        MintManager.getInstance(context).setPreferredUnit("sat")
    }

    @After
    fun tearDown() {
        BitcoinPriceWorker.isTesting = false
    }

    private fun sale(
        amount: Long,
        entryUnit: String,
        enteredAmount: Long,
        unit: String = "sat",
    ) = PaymentHistoryEntry(
        token = "",
        amount = amount,
        date = Date(),
        rawUnit = unit,
        rawEntryUnit = entryUnit,
        enteredAmount = enteredAmount,
        rawStatus = PaymentHistoryEntry.STATUS_COMPLETED,
    )

    private fun seedHistory(vararg entries: PaymentHistoryEntry) {
        context.getSharedPreferences("PaymentHistory", Context.MODE_PRIVATE).edit()
            .putString("history", Gson().toJson(entries.toList()))
            .commit()
    }

    @Test
    fun `in sats, sales keyed in sats and in fiat both count`() {
        seedHistory(
            sale(amount = 1_000, entryUnit = "sat", enteredAmount = 1_000),
            sale(amount = 5_000, entryUnit = "USD", enteredAmount = 450),
            // Received as a mint's "usd" ecash, so not part of the sats summary
            sale(amount = 450, entryUnit = "usd", enteredAmount = 450, unit = "usd"),
        )

        val data = InsightsRepository.compute(context, InsightsRange.DAY)

        assertEquals(2, data.periodTxCount)
        assertEquals(6_000L, data.periodTotalSats)
    }

    @Test
    fun `a sale keyed in dollars is worth what was charged, whatever bitcoin does after`() {
        val sale = sale(amount = 10_109, entryUnit = "USD", enteredAmount = 850)
            .copyWith(bitcoinPrice = 84_079.72)

        // Today's price is far above the sale's; the sale still reads $8.50
        val value = InsightsRepository.valueAtSale(sale, "USD", currentBtcPrice = 101_600.0)

        assertEquals(850L, value.fiatMinor)
        assertEquals(10_109L, value.sats)
    }

    @Test
    fun `a sale keyed in sats falls back when its recorded rate has no currency`() {
        val sale = sale(amount = 10_000, entryUnit = "sat", enteredAmount = 10_000)
            .copyWith(bitcoinPrice = 80_000.0)

        val value = InsightsRepository.valueAtSale(sale, "USD", currentBtcPrice = 120_000.0)

        assertEquals(1_200L, value.fiatMinor)
    }

    @Test
    fun `changing to yen does not interpret a sats sale's dollar rate as yen`() {
        val sale = sale(amount = 11_000, entryUnit = "sat", enteredAmount = 10_000)
            .copyWith(bitcoinPrice = 80_000.0, tipAmountSats = 1_000)

        val value = InsightsRepository.valueAtSale(sale, "JPY", currentBtcPrice = 12_000_000.0)

        assertEquals(10_000L, value.sats)
        assertEquals(120_000L, value.fiatMinor)
        assertEquals(1_000L, value.tipSats)
        assertEquals(12_000L, value.tipFiatMinor)
    }

    @Test
    fun `an unlabelled historical rate is unused when the current rate is unavailable`() {
        val sale = sale(amount = 11_000, entryUnit = "sat", enteredAmount = 10_000)
            .copyWith(bitcoinPrice = 80_000.0, tipAmountSats = 1_000)

        val value = InsightsRepository.valueAtSale(sale, "JPY", currentBtcPrice = null)

        assertEquals(0L, value.fiatMinor)
        assertEquals(0L, value.tipFiatMinor)
    }

    @Test
    fun `a sale keyed in another currency falls back to today's price`() {
        // A euro price can't value the sale in dollars
        val sale = sale(amount = 10_000, entryUnit = "EUR", enteredAmount = 700)
            .copyWith(bitcoinPrice = 70_000.0)

        val value = InsightsRepository.valueAtSale(sale, "USD", currentBtcPrice = 100_000.0)

        assertEquals(1_000L, value.fiatMinor)
    }

    @Test
    fun `tips are their own total, never part of sales`() {
        seedHistory(
            // $8.50 sale plus a 1,000 sat tip, paid at $80,000
            sale(amount = 11_000, entryUnit = "USD", enteredAmount = 850)
                .copyWith(bitcoinPrice = 80_000.0, tipAmountSats = 1_000),
            sale(amount = 5_000, entryUnit = "USD", enteredAmount = 400)
                .copyWith(bitcoinPrice = 80_000.0),
        )

        val data = InsightsRepository.compute(context, InsightsRange.DAY)

        assertEquals(2, data.periodTxCount)
        assertEquals(15_000L, data.periodTotalSats)
        assertEquals(1_250L, data.periodTotalFiatMinor)
        assertEquals(1_000L, data.periodTipSats)
        assertEquals(80L, data.periodTipFiatMinor)
        assertEquals(1_000L, data.buckets.last().tipSats)
        assertEquals(setOf(850L, 400L), data.transactions.map { it.totalFiatMinor }.toSet())
    }

    @Test
    fun `yen ecash tips use internal minor units in period and day totals`() {
        MintManager.getInstance(context).setPreferredUnit("jpy")
        seedHistory(
            sale(amount = 1_100, entryUnit = "JPY", enteredAmount = 100_000, unit = "jpy")
                .copyWith(bitcoinPrice = null, tipAmountSats = 100),
            sale(amount = 2_200, entryUnit = "JPY", enteredAmount = 200_000, unit = "jpy")
                .copyWith(bitcoinPrice = null, tipAmountSats = 200),
        )

        val data = InsightsRepository.compute(context, InsightsRange.DAY)

        assertEquals(300_000L, data.periodTotalFiatMinor)
        assertEquals(30_000L, data.periodTipFiatMinor)
        assertEquals(30_000L, data.buckets.last().tipFiatMinor)
        assertEquals(
            "¥300",
            InsightsFormatter.format(
                DisplayUnit.FIAT, data.periodTipSats, data.periodTipFiatMinor, data.fiatCurrency,
            ),
        )
    }

    @Test
    fun `dollar ecash tips already use minor units in period and day totals`() {
        MintManager.getInstance(context).setPreferredUnit("usd")
        seedHistory(
            sale(amount = 1_100, entryUnit = "USD", enteredAmount = 1_000, unit = "usd")
                .copyWith(bitcoinPrice = null, tipAmountSats = 100),
        )

        val data = InsightsRepository.compute(context, InsightsRange.DAY)

        assertEquals(1_000L, data.periodTotalFiatMinor)
        assertEquals(100L, data.periodTipFiatMinor)
        assertEquals(100L, data.buckets.last().tipFiatMinor)
    }

    private fun PaymentHistoryEntry.copyWith(
        bitcoinPrice: Double?,
        tipAmountSats: Long = 0,
    ) = PaymentHistoryEntry(
        id = id,
        token = token,
        amount = amount,
        date = date,
        rawUnit = getUnit(),
        rawEntryUnit = getEntryUnit(),
        enteredAmount = enteredAmount,
        bitcoinPrice = bitcoinPrice,
        rawStatus = status,
        tipAmountSats = tipAmountSats,
    )

    @Test
    fun `legacy sat units still count in sats`() {
        assertTrue(InsightsRepository.isInBaseUnit(sale(1, "sat", 1, unit = "sats"), "sat"))
        assertTrue(InsightsRepository.isInBaseUnit(sale(1, "sat", 1, unit = "btc"), "sat"))
    }

    @Test
    fun `a custom unit counts only payments received in that unit`() {
        assertTrue(InsightsRepository.isInBaseUnit(sale(450, "usd", 450, unit = "usd"), "usd"))
        assertFalse(InsightsRepository.isInBaseUnit(sale(5_000, "USD", 450), "usd"))
    }
}
