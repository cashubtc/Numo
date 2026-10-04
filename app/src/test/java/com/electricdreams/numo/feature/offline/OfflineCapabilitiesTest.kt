package com.electricdreams.numo.feature.offline

import com.electricdreams.numo.feature.offline.OfflineCapabilities.Config
import com.electricdreams.numo.feature.offline.OfflineCapabilities.Feature
import com.electricdreams.numo.feature.offline.OfflineCapabilities.Group
import com.electricdreams.numo.feature.offline.OfflineCapabilities.Row
import com.electricdreams.numo.feature.offline.OfflineCapabilities.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OfflineCapabilitiesTest {

    private val updatedAt = 1_700_000_000_000L

    private val baseline = Config(
        autoWithdrawEnabled = false,
        usesBtcPayCatalog = false,
        priceUpdatedAt = updatedAt,
    )

    @Test
    fun `given a default setup, then what needs internet comes first, led by new charges`() {
        val groups = OfflineCapabilities.groups(baseline)

        assertEquals(listOf(Group.NEEDS_INTERNET, Group.WORKS_OFFLINE), groups.keys.toList())
        assertEquals(
            listOf(
                Row(Feature.NEW_CHARGES, Status.UNAVAILABLE),
                Row(Feature.WITHDRAWALS, Status.UNAVAILABLE),
            ),
            groups[Group.NEEDS_INTERNET]
        )
        assertEquals(
            listOf(
                Row(Feature.CATALOG, Status.AVAILABLE),
                Row(Feature.HISTORY, Status.AVAILABLE),
                Row(Feature.EXCHANGE_RATE, Status.DEGRADED, updatedAt),
            ),
            groups[Group.WORKS_OFFLINE]
        )
    }

    @Test
    fun `given opened from a payment, then this payment leads the limited rows after the checks`() {
        val worksOffline = OfflineCapabilities.groups(
            baseline.copy(paymentOnScreen = true)
        )[Group.WORKS_OFFLINE].orEmpty()

        assertEquals(
            listOf(
                Row(Feature.CATALOG, Status.AVAILABLE),
                Row(Feature.HISTORY, Status.AVAILABLE),
                Row(Feature.PAYMENT_ON_SCREEN, Status.DEGRADED),
                Row(Feature.EXCHANGE_RATE, Status.DEGRADED, updatedAt),
            ),
            worksOffline
        )
    }

    @Test
    fun `given not opened from a payment, then there is no this payment row`() {
        val features = OfflineCapabilities.rows(baseline).map { it.feature }

        assertFalse(Feature.PAYMENT_ON_SCREEN in features)
    }

    @Test
    fun `given auto-withdraw is off, then its row is hidden`() {
        val features = OfflineCapabilities.rows(baseline).map { it.feature }

        assertFalse(Feature.AUTO_WITHDRAW in features)
    }

    @Test
    fun `given auto-withdraw is on, then it needs internet after withdrawals`() {
        val needsInternet = OfflineCapabilities.groups(
            baseline.copy(autoWithdrawEnabled = true)
        )[Group.NEEDS_INTERNET].orEmpty().map { it.feature }

        assertEquals(
            listOf(Feature.NEW_CHARGES, Feature.WITHDRAWALS, Feature.AUTO_WITHDRAW),
            needsInternet
        )
    }

    @Test
    fun `given BTCPay POS catalog, then the catalog needs internet`() {
        val groups = OfflineCapabilities.groups(baseline.copy(usesBtcPayCatalog = true))

        assertEquals(
            listOf(Feature.NEW_CHARGES, Feature.CATALOG, Feature.WITHDRAWALS),
            groups[Group.NEEDS_INTERNET].orEmpty().map { it.feature }
        )
        assertEquals(
            listOf(Feature.HISTORY, Feature.EXCHANGE_RATE),
            groups[Group.WORKS_OFFLINE].orEmpty().map { it.feature }
        )
    }

    @Test
    fun `given no cached exchange rate, then the rate needs internet`() {
        val rows = OfflineCapabilities.rows(baseline.copy(priceUpdatedAt = null))

        val rate = rows.first { it.feature == Feature.EXCHANGE_RATE }
        assertEquals(Row(Feature.EXCHANGE_RATE, Status.UNAVAILABLE), rate)
        assertEquals(Group.NEEDS_INTERNET, rate.group)
    }
}
