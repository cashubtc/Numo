package com.electricdreams.numo.feature.items.handlers

import com.electricdreams.numo.core.model.AssetId
import com.electricdreams.numo.core.model.AtomicAmount
import com.electricdreams.numo.core.model.UnitId
import org.junit.Assert.assertEquals
import org.junit.Test

class VatCalculatorAtomicTest {

    private val points = AssetId.global(UnitId.of("points"))

    @Test
    fun `gross input remains authoritative for coarse custom unit`() {
        val result = VatCalculator.calculateAtomicAmounts(
            enteredAmount = AtomicAmount(5L, points),
            vatRate = 10,
            priceIncludesVat = true,
        )

        assertEquals(5L, result.net.value)
        assertEquals(0L, result.vat.value)
        assertEquals(5L, result.gross.value)
    }

    @Test
    fun `net input rounds gross half up`() {
        val result = VatCalculator.calculateAtomicAmounts(
            enteredAmount = AtomicAmount(5L, points),
            vatRate = 10,
            priceIncludesVat = false,
        )

        assertEquals(5L, result.net.value)
        assertEquals(1L, result.vat.value)
        assertEquals(6L, result.gross.value)
    }
}
