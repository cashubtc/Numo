package com.electricdreams.numo.feature.settings

import org.junit.Assert.*
import org.junit.Test

class CurrencyAdapterTest {

    @Test
    fun `Custom currency map contains expected currencies`() {
        val currencies = CurrencyItem.Custom.getAllCurrencies()
        
        // Verify we have the expected number of currencies
        assertTrue(currencies.size > 120)
        
        // Verify specific currencies are present
        assertTrue(CurrencyItem.Custom.isSupported("CUP"))
        assertTrue(CurrencyItem.Custom.isSupported("MLC"))
        assertTrue(CurrencyItem.Custom.isSupported("USD"))
        assertTrue(CurrencyItem.Custom.isSupported("EUR"))
        assertTrue(CurrencyItem.Custom.isSupported("DKK"))
        assertTrue(CurrencyItem.Custom.isSupported("SEK"))
        assertTrue(CurrencyItem.Custom.isSupported("NOK"))
        assertTrue(CurrencyItem.Custom.isSupported("COP"))
        assertTrue(CurrencyItem.Custom.isSupported("JPY"))
    }

    @Test
    fun `Custom currency map returns correct info`() {
        val cup = CurrencyItem.Custom.getInfo("CUP")
        assertNotNull(cup)
        assertEquals("CUP", cup!!.currencyCode)
        assertEquals("Cuban Peso", cup.displayName)
        
        val eur = CurrencyItem.Custom.getInfo("EUR")
        assertNotNull(eur)
        assertEquals("EUR", eur!!.currencyCode)
        assertEquals("Euro", eur.displayName)
    }

    @Test
    fun `Custom currency map handles case insensitivity`() {
        assertTrue(CurrencyItem.Custom.isSupported("cup"))
        assertTrue(CurrencyItem.Custom.isSupported("Cup"))
        assertTrue(CurrencyItem.Custom.isSupported("CUP"))
        
        val cup = CurrencyItem.Custom.getInfo("cup")
        assertNotNull(cup)
        assertEquals("CUP", cup!!.currencyCode)
    }

    @Test
    fun `Custom currency map returns null for unsupported currency`() {
        val invalid = CurrencyItem.Custom.getInfo("INVALID")
        assertNull(invalid)
        assertFalse(CurrencyItem.Custom.isSupported("INVALID"))
    }

    @Test
    fun `Standard currency has correct code`() {
        val standard = CurrencyItem.Standard(java.util.Currency.getInstance("USD"))
        assertEquals("USD", standard.currencyCode)
    }

    @Test
    fun `Custom currency has correct properties`() {
        val custom = CurrencyItem.Custom("TST", "Test Currency", "T")
        assertEquals("TST", custom.currencyCode)
        assertEquals("Test Currency", custom.displayName)
        assertEquals("T", custom.symbol)
    }
}
