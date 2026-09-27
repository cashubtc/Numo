package com.electricdreams.numo.feature.insights

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.electricdreams.numo.core.data.model.PaymentHistoryEntry
import com.electricdreams.numo.core.util.MintManager
import com.google.gson.Gson
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
        context = ApplicationProvider.getApplicationContext()
        MintManager.getInstance(context).setPreferredUnit("sat")
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
