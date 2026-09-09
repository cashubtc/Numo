package com.electricdreams.numo.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class UnitAmountFormatterTest {

    @Test
    fun `formatter honors ISO currency fraction digits`() {
        assertEquals(
            "$12.34",
            UnitAmountFormatter.formatAtomic(
                1234,
                UnitDescriptor.defaultFor(UnitId.of("usd"), Locale.US),
                Locale.US,
            ),
        )
        val bhd = UnitAmountFormatter.formatAtomic(
            1234,
            UnitDescriptor.defaultFor(UnitId.of("bhd"), Locale.US),
            Locale.US,
        )
        assertTrue(bhd.contains("BHD"))
        assertTrue(bhd.endsWith("1.234"))

        val jpy = UnitAmountFormatter.formatAtomic(
            12,
            UnitDescriptor.defaultFor(UnitId.of("jpy"), Locale.US),
            Locale.US,
        )
        assertTrue(jpy.contains("12"))
        assertFalse(jpy.contains('.'))
    }

    @Test
    fun `formatter keeps custom atomic units whole by default`() {
        val descriptor = UnitDescriptor.defaultFor(UnitId.of("points"), Locale.US)

        assertEquals("1,234 POINTS", UnitAmountFormatter.formatAtomic(1234, descriptor, Locale.US))
    }

    @Test
    fun `asset formatter distinguishes a mint scoped custom unit`() {
        val amount = AtomicAmount(
            value = 42,
            asset = AssetId.mintScoped(UnitId.of("points"), "https://mint.example/path"),
        )

        assertEquals("42 POINTS · mint.example", UnitAmountFormatter.formatAsset(amount, Locale.US))
    }

    @Test
    fun `custom balances keep identically named assets separate by issuer`() {
        val balances = mapOf("https://mint-b.example" to 7L, "https://mint-a.example" to 25L)
        assertEquals(
            "25 POINTS · mint-a.example + 7 POINTS · mint-b.example",
            UnitAmountFormatter.formatBalances(balances, UnitId.of("points"), Locale.US),
        )
        assertEquals("$0.32", UnitAmountFormatter.formatBalances(balances, UnitId.of("usd"), Locale.US))
    }

    @Test
    fun `asset formatter does not add issuer text for a global unit`() {
        val amount = AtomicAmount(42, AssetId.global(UnitId.of("points")))

        assertEquals("42 POINTS", UnitAmountFormatter.formatAsset(amount, Locale.US))
    }
}
