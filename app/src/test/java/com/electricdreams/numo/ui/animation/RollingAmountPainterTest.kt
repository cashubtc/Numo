package com.electricdreams.numo.ui.animation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RollingAmountPainterTest {
    @Test
    fun `carry into a new integer column preserves place identity`() {
        val before = RollingAmountPainter.glyphs("$99.50")
        val after = RollingAmountPainter.glyphs("$100.50")
        assertEquals(before[2].key, after[3].key)
        assertEquals(before[4], after[5])
        assertEquals(before[5], after[6])
        assertEquals(before[0], after[0])
        assertNotEquals(after[1].key, after[2].key)
    }

    @Test
    fun `locale decimal separator keeps fractional digits in place`() {
        val before = RollingAmountPainter.glyphs("999,50 €", ',')
        val after = RollingAmountPainter.glyphs("1.000,50 €", ',')
        assertEquals(before[4], after[6])
        assertEquals(before[5], after[7])
        assertEquals(before.last(), after.last())
        assertEquals("decimal", after[5].key)
    }

    @Test
    fun `grouping separator and sats suffix do not become digit columns`() {
        val glyphs = RollingAmountPainter.glyphs("24,750 sats")
        val digits = glyphs.filter { it.character.isDigit() }
        assertEquals(listOf("digit:4", "digit:3", "digit:2", "digit:1", "digit:0"), digits.map { it.key })
        assertEquals(glyphs.size, glyphs.map { it.key }.distinct().size)
    }
}
