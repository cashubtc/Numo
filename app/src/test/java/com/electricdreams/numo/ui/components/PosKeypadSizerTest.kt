package com.electricdreams.numo.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class PosKeypadSizerTest {

    @Test
    fun `tall screen keeps the usual keypad height`() {
        val height = PosKeypadSizer.fitKeypadHeight(
            available = 850,
            aboveAndBelow = 310,
            stripReserve = 56,
            minHeight = 192,
            maxHeight = 360,
        )

        assertEquals(360, height)
    }

    @Test
    fun `short screen gives the keypad what the amount and offline strip leave over`() {
        // Sunmi V2s: 720dp tall, minus 24dp status bar and 48dp navigation bar
        val height = PosKeypadSizer.fitKeypadHeight(
            available = 648,
            aboveAndBelow = 310,
            stripReserve = 56,
            minHeight = 192,
            maxHeight = 360,
        )

        assertEquals(282, height)
    }

    @Test
    fun `keypad never shrinks below its minimum`() {
        val height = PosKeypadSizer.fitKeypadHeight(
            available = 480,
            aboveAndBelow = 310,
            stripReserve = 56,
            minHeight = 192,
            maxHeight = 360,
        )

        assertEquals(192, height)
    }
}
