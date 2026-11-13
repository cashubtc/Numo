package com.electricdreams.numo.feature.history

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.electricdreams.numo.core.data.model.PaymentHistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoryFilterTest {

    private val zone = TimeZone.getTimeZone("Europe/Berlin")
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs().edit().clear().commit()
    }

    private fun prefs() = context.getSharedPreferences("PaymentHistory", Context.MODE_PRIVATE)

    /** Local time in [zone]. */
    private fun at(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(year, month - 1, day, hour, minute)
        }.timeInMillis

    private fun utcMidnight(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, month - 1, day)
        }.timeInMillis

    private fun entry(millis: Long, pending: Boolean) = PaymentHistoryEntry(
        id = "id-$millis",
        token = "",
        amount = 100L,
        date = Date(millis),
        enteredAmount = 100L,
        rawStatus = if (pending) PaymentHistoryEntry.STATUS_PENDING else PaymentHistoryEntry.STATUS_COMPLETED,
    )

    private val now = at(2026, 9, 24, 14, 30)

    @Test
    fun `any time has no date range and no active filters`() {
        val filter = HistoryFilter()
        assertNull(filter.dateRange(now, zone))
        assertEquals(0, filter.activeCount)
    }

    @Test
    fun `today covers the whole local day`() {
        val range = HistoryFilter(datePreset = HistoryDatePreset.TODAY).dateRange(now, zone)
        assertEquals(at(2026, 9, 24), range?.first)
        assertEquals(at(2026, 9, 25) - 1, range?.last)
    }

    @Test
    fun `last 7 days is today and the six days before`() {
        val range = HistoryFilter(datePreset = HistoryDatePreset.LAST_7_DAYS).dateRange(now, zone)
        assertEquals(at(2026, 9, 18), range?.first)
        assertEquals(at(2026, 9, 25) - 1, range?.last)
    }

    @Test
    fun `last 30 days is today and the 29 days before`() {
        val range = HistoryFilter(datePreset = HistoryDatePreset.LAST_30_DAYS).dateRange(now, zone)
        assertEquals(at(2026, 8, 26), range?.first)
    }

    @Test
    fun `this month starts on the first`() {
        val range = HistoryFilter(datePreset = HistoryDatePreset.THIS_MONTH).dateRange(now, zone)
        assertEquals(at(2026, 9, 1), range?.first)
        assertEquals(at(2026, 9, 25) - 1, range?.last)
    }

    @Test
    fun `presets stay relative to the current day`() {
        val filter = HistoryFilter(datePreset = HistoryDatePreset.TODAY)
        val tomorrow = at(2026, 9, 25, 9)
        assertEquals(at(2026, 9, 25), filter.dateRange(tomorrow, zone)?.first)
    }

    @Test
    fun `custom range covers the picked local days end to end`() {
        val filter = HistoryFilter(
            datePreset = HistoryDatePreset.CUSTOM,
            customStartUtc = utcMidnight(2026, 9, 1),
            customEndUtc = utcMidnight(2026, 9, 3),
        )
        val range = filter.dateRange(now, zone)
        assertEquals(at(2026, 9, 1), range?.first)
        assertEquals(at(2026, 9, 4) - 1, range?.last)
        assertTrue(filter.matches(entry(at(2026, 9, 3, 23, 59), pending = false), now, zone))
        assertFalse(filter.matches(entry(at(2026, 9, 4, 0, 1), pending = false), now, zone))
    }

    @Test
    fun `status filters match paid and pending entries`() {
        val paid = entry(now, pending = false)
        val pending = entry(now, pending = true)
        val paidOnly = HistoryFilter(status = HistoryStatusFilter.PAID)
        val pendingOnly = HistoryFilter(status = HistoryStatusFilter.PENDING)

        assertTrue(paidOnly.matches(paid, now, zone))
        assertFalse(paidOnly.matches(pending, now, zone))
        assertTrue(pendingOnly.matches(pending, now, zone))
        assertFalse(pendingOnly.matches(paid, now, zone))
    }

    @Test
    fun `active count and removing single filters`() {
        val filter = HistoryFilter(
            status = HistoryStatusFilter.PENDING,
            datePreset = HistoryDatePreset.LAST_7_DAYS,
        )
        assertEquals(2, filter.activeCount)
        assertEquals(HistoryStatusFilter.ALL, filter.withoutStatus().status)
        assertEquals(HistoryDatePreset.LAST_7_DAYS, filter.withoutStatus().datePreset)
        assertEquals(HistoryDatePreset.ANY_TIME, filter.withoutDate().datePreset)
        assertEquals(1, filter.withoutDate().activeCount)
    }

    @Test
    fun `save and load round-trips presets without timestamps`() {
        val filter = HistoryFilter(status = HistoryStatusFilter.PAID, datePreset = HistoryDatePreset.LAST_30_DAYS)
        HistoryFilter.save(prefs(), filter)

        assertEquals(filter, HistoryFilter.load(prefs()))
        assertEquals(0L, prefs().getLong(HistoryFilter.KEY_DATE_START, -1L))
    }

    @Test
    fun `a stored range without a preset loads as custom`() {
        prefs().edit()
            .putLong(HistoryFilter.KEY_DATE_START, 1_000L)
            .putLong(HistoryFilter.KEY_DATE_END, 2_000L)
            .commit()

        val filter = HistoryFilter.load(prefs())
        assertEquals(HistoryDatePreset.CUSTOM, filter.datePreset)
        assertEquals(1_000L, filter.customStartUtc)
    }

    @Test
    fun `unknown stored values fall back to showing everything`() {
        prefs().edit()
            .putInt(HistoryFilter.KEY_STATUS, 99)
            .putString(HistoryFilter.KEY_DATE_PRESET, "nonsense")
            .commit()

        assertEquals(HistoryFilter(), HistoryFilter.load(prefs()))
    }

    @Test
    fun `labels read naturally`() {
        assertEquals("Pending", HistoryStatusFilter.PENDING.label(context))
        assertEquals("Last 7 days", HistoryFilter(datePreset = HistoryDatePreset.LAST_7_DAYS).dateLabel(context))
        val custom = HistoryFilter(
            datePreset = HistoryDatePreset.CUSTOM,
            customStartUtc = utcMidnight(2026, 9, 1),
            customEndUtc = utcMidnight(2026, 9, 20),
        )
        assertEquals("Sep 1 – Sep 20", custom.dateLabel(context))
    }
}
