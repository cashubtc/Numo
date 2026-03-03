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
    fun `toString formats correctly`() {
        // Force US locale for consistent testing of USD
        val usd = Amount(1050, Amount.Currency.USD)
        // Locale.US uses period
        assertEquals("$10.50", usd.toString())

        // DKK uses comma
        val dkk = Amount(1050, Amount.Currency.DKK)
        // Locale("da", "DK") uses comma for decimal and period for thousands
        // We expect "kr.10,50" or "kr. 10,50" depending on NumberFormat implementation
        // Broad check for prefix and suffix
        val dkkStr = dkk.toString()
        assert(dkkStr.startsWith("kr."))
        assert(dkkStr.contains("10,50"))
        
        // SEK uses comma
        val sek = Amount(1050, Amount.Currency.SEK)
        val sekStr = sek.toString()
        assert(sekStr.startsWith("kr"))
        assert(sekStr.contains("10,50"))
    }
}
