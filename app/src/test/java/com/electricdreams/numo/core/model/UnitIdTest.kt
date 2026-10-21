package com.electricdreams.numo.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Locale

class UnitIdTest {

    @Test
    fun `unit identifiers are canonicalized without locale dependent casing`() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))

            assertEquals("usd", UnitId.of(" USD ").value)
            assertEquals("points", UnitId.of("POINTS").value)
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun `legacy sats alias resolves to sat`() {
        assertEquals(UnitId.SAT, UnitId.of("sats"))
    }

    @Test
    fun `blank whitespace and control characters are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { UnitId.of(" ") }
        assertThrows(IllegalArgumentException::class.java) { UnitId.of("loyalty points") }
        assertThrows(IllegalArgumentException::class.java) { UnitId.of("poi\nnts") }
        assertNull(UnitId.ofOrNull(null))
    }
}
