package com.electricdreams.numo.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class TipAmountCalculatorTest {

    @Test
    fun `percentage tip keeps existing integer rounding behavior`() {
        assertEquals(15L, TipAmountCalculator.percentageTip(101L, 15))
    }

    @Test
    fun `percentage tip does not overflow intermediate multiplication`() {
        assertEquals(
            922_337_203_685_477_580L,
            TipAmountCalculator.percentageTip(Long.MAX_VALUE, 10),
        )
    }

    @Test(expected = ArithmeticException::class)
    fun `total rejects overflow`() {
        TipAmountCalculator.total(Long.MAX_VALUE, 1L)
    }
}
