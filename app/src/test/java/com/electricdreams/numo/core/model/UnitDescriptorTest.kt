package com.electricdreams.numo.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.Currency
import java.util.Locale

class UnitDescriptorTest {
    @Test
    fun `permissive Android currency lookup cannot give custom units fiat precision`() {
        val knownCurrencies = Currency.getAvailableCurrencies()
        val unknown = mock<Currency>()
        whenever(unknown.currencyCode).thenReturn("BUX")
        whenever(unknown.defaultFractionDigits).thenReturn(2)
        whenever(unknown.getSymbol(Locale.US)).thenReturn("BUX")
        mockStatic(Currency::class.java).use { currencies ->
            currencies.`when`<Currency> { Currency.getInstance("BUX") }.thenReturn(unknown)
            currencies.`when`<Set<Currency>> { Currency.getAvailableCurrencies() }
                .thenReturn(knownCurrencies)
            val descriptor = UnitDescriptor.defaultFor(UnitId.of("bux"), Locale.US)
            assertEquals(UnitKind.CUSTOM, descriptor.kind)
            assertEquals(0, descriptor.fractionDigits)
            assertEquals("25 BUX", UnitAmountFormatter.formatAtomic(25, descriptor, Locale.US))
            assertTrue(Amount.Currency.fromCode("BUX").isZeroDecimal())
        }
    }

    @Test
    fun `known ISO currencies retain their precision`() {
        assertEquals(2, UnitDescriptor.defaultFor(UnitId.of("usd")).fractionDigits)
        assertEquals(0, UnitDescriptor.defaultFor(UnitId.of("jpy")).fractionDigits)
        assertEquals(3, UnitDescriptor.defaultFor(UnitId.of("kwd")).fractionDigits)
    }

    @Test
    fun `custom precision cannot be overridden through construction or copy`() {
        val unit = UnitId.of("points")
        val descriptor = UnitDescriptor.custom(unit, displayCode = "Points", symbol = "P")
        assertEquals(0, descriptor.fractionDigits)
        assertEquals("25 Points", UnitAmountFormatter.formatAtomic(25, descriptor, Locale.US))

        assertThrows(IllegalArgumentException::class.java) {
            descriptor.copy(fractionDigits = 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            UnitDescriptor(unit, "Points", "P", 3, UnitKind.CUSTOM)
        }
    }

    @Test
    fun `standardized units retain their defined precision`() {
        mapOf(
            "btc" to 8, "sat" to 0, "msat" to 0, "usdt" to 2,
            "usdc" to 2, "eurc" to 2, "gyen" to 0,
        ).forEach { (code, fractionDigits) ->
            assertEquals(
                code, fractionDigits, UnitDescriptor.defaultFor(UnitId.of(code)).fractionDigits,
            )
        }
    }
}
