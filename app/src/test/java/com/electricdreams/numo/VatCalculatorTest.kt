package com.electricdreams.numo

import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.feature.items.handlers.VatCalculator
import org.junit.Assert.assertEquals
import org.junit.Test

class VatCalculatorTest {

    @Test
    fun `calculateFiatBreakdown uses provided currency code`() {
        val breakdown = VatCalculator.calculateFiatBreakdown(
            enteredPrice = 120.0,
            vatRate = 20,
            priceIncludesVat = true,
            currency = "GBP",
        )

        // 120 gross at 20% VAT -> 100 net, 20 VAT
        // Just assert the numeric values via Amount parsing to avoid
        // locale-specific separators in direct string comparison.
        val net = Amount.parse(breakdown.netPrice, Amount.Currency.GBP)!!.minorUnits
        val vat = Amount.parse(breakdown.vatAmount, Amount.Currency.GBP)!!.minorUnits
        val gross = Amount.parse(breakdown.grossPrice, Amount.Currency.GBP)!!.minorUnits

        assertEquals(10_000L, net)   // £100.00
        assertEquals(2_000L, vat)    // £20.00
        assertEquals(12_000L, gross) // £120.00
    }
}
