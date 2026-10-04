package com.electricdreams.numo.feature.offline

import com.electricdreams.numo.feature.offline.OfflineCapabilities.Config
import com.electricdreams.numo.feature.offline.OfflineCapabilities.Feature
import com.electricdreams.numo.feature.offline.OfflineCapabilities.Row
import com.electricdreams.numo.feature.offline.OfflineCapabilities.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OfflineCapabilitiesTest {

    private val baseline = Config(
        autoWithdrawEnabled = false,
        usesBtcPayCatalog = false,
        priceUpdatedAt = 1_700_000_000_000L,
    )

    @Test
    fun `given a default setup, then rows go available, degraded, unavailable`() {
        val rows = OfflineCapabilities.rows(baseline)

        assertEquals(
            listOf(
                Row(Feature.CATALOG, Status.AVAILABLE),
                Row(Feature.HISTORY, Status.AVAILABLE),
                Row(Feature.EXCHANGE_RATE, Status.DEGRADED, 1_700_000_000_000L),
                Row(Feature.ACCEPT_PAYMENTS, Status.UNAVAILABLE),
                Row(Feature.WITHDRAWALS, Status.UNAVAILABLE),
            ),
            rows
        )
    }

    @Test
    fun `given auto-withdraw is off, then its row is hidden`() {
        val features = OfflineCapabilities.rows(baseline).map { it.feature }

        assertFalse(Feature.AUTO_WITHDRAW in features)
    }

    @Test
    fun `given auto-withdraw is on, then it is listed last as unavailable`() {
        val rows = OfflineCapabilities.rows(baseline.copy(autoWithdrawEnabled = true))

        assertEquals(Row(Feature.AUTO_WITHDRAW, Status.UNAVAILABLE), rows.last())
    }

    @Test
    fun `given BTCPay POS catalog, then the catalog moves to the unavailable group`() {
        val rows = OfflineCapabilities.rows(baseline.copy(usesBtcPayCatalog = true))

        assertEquals(
            listOf(Feature.HISTORY, Feature.EXCHANGE_RATE,
                Feature.CATALOG, Feature.ACCEPT_PAYMENTS, Feature.WITHDRAWALS),
            rows.map { it.feature }
        )
        assertEquals(Status.UNAVAILABLE, rows.first { it.feature == Feature.CATALOG }.status)
    }

    @Test
    fun `given no cached exchange rate, then the rate is unavailable`() {
        val rows = OfflineCapabilities.rows(baseline.copy(priceUpdatedAt = null))

        assertEquals(
            Row(Feature.EXCHANGE_RATE, Status.UNAVAILABLE),
            rows.first { it.feature == Feature.EXCHANGE_RATE }
        )
    }
}
