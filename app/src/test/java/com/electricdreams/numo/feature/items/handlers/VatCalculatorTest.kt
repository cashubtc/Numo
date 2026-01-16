package com.electricdreams.numo.feature.items.handlers

import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.core.util.CurrencyManager
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class VatCalculatorTest {

    @Test
    fun `calculateFiatBreakdown from Net Price`() {
        // Price: 10.00, VAT: 20%, Excludes VAT
        // Net: 10.00
        // VAT: 2.00
        // Gross: 12.00
        val result = VatCalculator.calculateFiatBreakdown(
            enteredPrice = 10.00,
            vatRate = 20,
            priceIncludesVat = false,
            currency = "USD"
        )

        assertEquals("$10.00", result.netPrice)
        assertEquals("$2.00", result.vatAmount)
        assertEquals("$12.00", result.grossPrice)
        assertEquals("VAT (20%)", result.vatLabel)
    }

    @Test
    fun `calculateFiatBreakdown from Gross Price`() {
        // Price: 12.00, VAT: 20%, Includes VAT
        // Net = 12.00 / 1.2 = 10.00
        // VAT = 12.00 - 10.00 = 2.00
        val result = VatCalculator.calculateFiatBreakdown(
            enteredPrice = 12.00,
            vatRate = 20,
            priceIncludesVat = true,
            currency = "USD"
        )

        assertEquals("$10.00", result.netPrice)
        assertEquals("$2.00", result.vatAmount)
        assertEquals("$12.00", result.grossPrice)
    }

    @Test
    fun `calculateSatsBreakdown from Net Price`() {
        // 1000 sats, 10% VAT
        // Net: 1000
        // VAT: 100
        // Gross: 1100
        val result = VatCalculator.calculateSatsBreakdown(
            enteredSats = 1000,
            vatRate = 10,
            priceIncludesVat = false
        )

        assertEquals("₿1,000", result.netPrice)
        assertEquals("₿100", result.vatAmount)
        assertEquals("₿1,100", result.grossPrice)
    }

    @Test
    fun `calculateSatsBreakdown from Gross Price`() {
        // 1100 sats, 10% VAT
        // Net = 1100 / 1.1 = 1000
        val result = VatCalculator.calculateSatsBreakdown(
            enteredSats = 1100,
            vatRate = 10,
            priceIncludesVat = true
        )

        assertEquals("₿1,000", result.netPrice)
        assertEquals("₿100", result.vatAmount)
        assertEquals("₿1,100", result.grossPrice)
    }

    @Test
    fun `calculateNetPriceForStorage returns original if VAT disabled`() {
        val result = VatCalculator.calculateNetPriceForStorage(
            enteredPrice = 100.0,
            vatEnabled = false,
            priceIncludesVat = true,
            vatRate = 20
        )
        assertEquals(100.0, result, 0.001)
    }

    @Test
    fun `calculateNetPriceForStorage returns original if entered as net`() {
        val result = VatCalculator.calculateNetPriceForStorage(
            enteredPrice = 100.0,
            vatEnabled = true,
            priceIncludesVat = false, // Entered as net
            vatRate = 20
        )
        assertEquals(100.0, result, 0.001)
    }

    @Test
    fun `calculateNetPriceForStorage strips VAT if entered as gross`() {
        // 120 entered as gross with 20% VAT -> 100 net
        val result = VatCalculator.calculateNetPriceForStorage(
            enteredPrice = 120.0,
            vatEnabled = true,
            priceIncludesVat = true,
            vatRate = 20
        )
        assertEquals(100.0, result, 0.001)
    }
}
