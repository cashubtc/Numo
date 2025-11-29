package com.electricdreams.numo

import com.electricdreams.numo.core.model.Amount
import org.junit.Assert.assertEquals
import org.junit.Test

class AmountTest {

    @Test
    fun `fromMajorUnits respects fiat decimals`() {
        val usd = Amount.fromMajorUnits(12.34, Amount.Currency.USD)
        assertEquals(1234L, usd.minorUnits)

        val eur = Amount.fromMajorUnits(9.99, Amount.Currency.EUR)
        assertEquals(999L, eur.minorUnits)
    }

    @Test
    fun `fromMajorUnits treats BTC value as sats`() {
        val btc = Amount.fromMajorUnits(123_456.0, Amount.Currency.BTC)
        assertEquals(123_456L, btc.minorUnits)
    }

    @Test
    fun `toString formats fiat with correct symbol and decimals`() {
        val usd = Amount.fromMinorUnits(1234, Amount.Currency.USD)
        assertEquals("$1.23", usd.toString())

        val eur = Amount.fromMinorUnits(1234, Amount.Currency.EUR)
        // EUR uses comma decimal by design
        assertEquals("€1,23", eur.toString())
    }

    @Test
    fun `parse parses formatted fiat strings`() {
        val usd = Amount.parse("$1.23", Amount.Currency.USD)!!
        assertEquals(123L, usd.minorUnits)

        val eur = Amount.parse("€1,23", Amount.Currency.EUR)!!
        assertEquals(123L, eur.minorUnits)
    }
}
