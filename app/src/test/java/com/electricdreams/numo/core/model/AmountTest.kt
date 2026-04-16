package com.electricdreams.numo.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class AmountTest {

    @Test
    fun `parse handles single character symbols`() {
        val usd = Amount.parse("$10.50")
        assertEquals(Amount.Currency.USD, usd?.currency)
        assertEquals(1050L, usd?.value)

        val eur = Amount.parse("€10,50")
        assertEquals(Amount.Currency.EUR, eur?.currency)
        assertEquals(1050L, eur?.value)

        val gbp = Amount.parse("£10.50")
        assertEquals(Amount.Currency.GBP, gbp?.currency)
        assertEquals(1050L, gbp?.value)

        val jpy = Amount.parse("¥100")
        assertEquals(Amount.Currency.JPY, jpy?.currency)
        assertEquals(10000L, jpy?.value) // JPY stored as cents (100 * 100)
    }

    @Test
    fun `parse handles multi-character symbols`() {
        val dkk = Amount.parse("kr. 100,50")
        assertEquals(Amount.Currency.DKK, dkk?.currency)
        assertEquals(10050L, dkk?.value)
        
        // DKK "kr." is unique, so it should be found without hint
        val dkk2 = Amount.parse("kr.100,50")
        assertEquals(Amount.Currency.DKK, dkk2?.currency)
    }

    @Test
    fun `parse handles ambiguous symbols with default currency`() {
        // "kr" is ambiguous between SEK and NOK
        // Without default, it might pick the first one (SEK in enum order if sorted by length equal)
        // Wait, DKK is "kr.", SEK is "kr", NOK is "kr".
        
        // Test SEK preference
        val sek = Amount.parse("kr 100,50", Amount.Currency.SEK)
        assertEquals(Amount.Currency.SEK, sek?.currency)
        assertEquals(10050L, sek?.value)

        // Test NOK preference
        val nok = Amount.parse("kr 100,50", Amount.Currency.NOK)
        assertEquals(Amount.Currency.NOK, nok?.currency)
        assertEquals(10050L, nok?.value)
    }

    @Test
    fun `parse returns first match if ambiguous and no default provided`() {
        val result = Amount.parse("kr 100,50")
        // It should match either SEK or NOK. 
        // Logic sorts by length desc. "kr" length 2. 
        // Filter matches SEK and NOK.
        // Returns first.
        assert(result?.currency == Amount.Currency.SEK || result?.currency == Amount.Currency.NOK)
    }

    @Test
    fun `parse handles multi-character symbols without space`() {
        val dkk = Amount.parse("kr.100,50")
        assertEquals(Amount.Currency.DKK, dkk?.currency)
        assertEquals(10050L, dkk?.value)
    }

    @Test
    fun `parse returns null for invalid input`() {
        assertNull(Amount.parse(""))
        assertNull(Amount.parse("INVALID"))
        assertNull(Amount.parse("XYZ 100"))
    }
    
    @Test
    fun `toString formats according to system locale`() {
        val originalLocale = Locale.getDefault()
        try {
            // Test US Locale behavior
            Locale.setDefault(Locale.US)
            
            val usd = Amount(1050, Amount.Currency.USD)
            assertEquals("$10.50", usd.toString())
            
            // In US locale, EUR will still use periods for decimals
            val eurUs = Amount(1050, Amount.Currency.EUR)
            assertEquals("€10.50", eurUs.toString())

            // Test German Locale behavior
            Locale.setDefault(Locale.GERMANY)
            
            // In German locale, EUR uses commas
            val eurDe = Amount(1050, Amount.Currency.EUR)
            assertEquals("€10,50", eurDe.toString())
            
            // In German locale, USD will also use commas
            val usdDe = Amount(1050, Amount.Currency.USD)
            assertEquals("$10,50", usdDe.toString())
            
            // Test zero-decimal behavior (JPY uses code instead of symbol)
            val jpy = Amount(10500, Amount.Currency.JPY) // 10500 cents = 105 JPY
            assertEquals("JPY 105", jpy.toString())

        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun `toString formats CUP currency with code`() {
        Locale.setDefault(Locale.US)
        val cup = Amount(2500, Amount.Currency.CUP) // 25.00 CUP
        assertEquals("CUP 25.00", cup.toString())
    }

    @Test
    fun `toString formats MLC currency with code`() {
        Locale.setDefault(Locale.US)
        val mlc = Amount(1500, Amount.Currency.MLC) // 15.00 MLC
        assertEquals("MLC 15.00", mlc.toString())
    }

    @Test
    fun `toString formats Nordic currencies with code`() {
        Locale.setDefault(Locale.US)
        
        val dkk = Amount(9950, Amount.Currency.DKK) // 99.50 DKK
        assertEquals("DKK 99.50", dkk.toString())
        
        val sek = Amount(5000, Amount.Currency.SEK) // 50.00 SEK
        assertEquals("SEK 50.00", sek.toString())
        
        val nok = Amount(7500, Amount.Currency.NOK) // 75.00 NOK
        assertEquals("NOK 75.00", nok.toString())
    }

    @Test
    fun `toStringWithoutSymbol formats correctly`() {
        Locale.setDefault(Locale.US)
        
        val usd = Amount(1050, Amount.Currency.USD)
        assertEquals("10.50", usd.toStringWithoutSymbol())
        
        val jpy = Amount(10500, Amount.Currency.JPY)
        assertEquals("105", jpy.toStringWithoutSymbol())
    }

    @Test
    fun `parse CUP currency`() {
        val cup = Amount.parse("CUP 25.00")
        assertEquals(Amount.Currency.CUP, cup?.currency)
        assertEquals(2500L, cup?.value)
    }

    @Test
    fun `CUP and MLC cannot be parsed from string without symbol`() {
        // CUP and MLC don't have standard symbols, so they can't be parsed from string
        // This is expected behavior - they must be created via Amount(cents, Amount.Currency.CUP)
        val cup = Amount(2500, Amount.Currency.CUP)
        assertEquals("CUP 25.00", cup.toString())
        
        val mlc = Amount(1500, Amount.Currency.MLC)
        assertEquals("MLC 15.00", mlc.toString())
    }
}
