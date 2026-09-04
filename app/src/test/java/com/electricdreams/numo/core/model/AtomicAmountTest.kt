package com.electricdreams.numo.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigDecimal
import java.util.Locale

class AtomicAmountTest {

    private val sat = AssetId.global(UnitId.SAT)
    private val usd = AssetId.global(UnitId.of("usd"))

    @Test
    fun `checked arithmetic succeeds only for the same asset`() {
        assertEquals(AtomicAmount(7, sat), AtomicAmount(3, sat) + AtomicAmount(4, sat))
        assertEquals(AtomicAmount(8, sat), AtomicAmount(10, sat) - AtomicAmount(2, sat))
        assertEquals(AtomicAmount(12, sat), AtomicAmount(3, sat) * 4)

        assertThrows(IllegalArgumentException::class.java) {
            AtomicAmount(1, sat) + AtomicAmount(1, usd)
        }
    }

    @Test
    fun `same custom unit from different issuers cannot be combined`() {
        val points = UnitId.of("points")
        val mintA = AssetId.mintScoped(points, "https://mint-a.example")
        val mintB = AssetId.mintScoped(points, "https://mint-b.example")

        assertThrows(IllegalArgumentException::class.java) {
            AtomicAmount(10, mintA) + AtomicAmount(10, mintB)
        }
    }

    @Test
    fun `arithmetic rejects underflow and overflow`() {
        assertThrows(IllegalArgumentException::class.java) {
            AtomicAmount(1, sat) - AtomicAmount(2, sat)
        }
        assertThrows(ArithmeticException::class.java) {
            AtomicAmount(Long.MAX_VALUE, sat) + AtomicAmount(1, sat)
        }
        assertThrows(ArithmeticException::class.java) {
            AtomicAmount(Long.MAX_VALUE, sat) * 2
        }
    }

    @Test
    fun `major unit conversion honors zero two three and eight fraction digits`() {
        val jpyDescriptor = UnitDescriptor.defaultFor(UnitId.of("jpy"), Locale.JAPAN)
        val usdDescriptor = UnitDescriptor.defaultFor(UnitId.of("usd"), Locale.US)
        val bhdDescriptor = UnitDescriptor.defaultFor(UnitId.of("bhd"), Locale.US)
        val btcDescriptor = UnitDescriptor.defaultFor(UnitId.BTC, Locale.US)

        assertEquals(
            AtomicAmount(12, AssetId.global(UnitId.of("jpy"))),
            AtomicAmount.fromMajorUnits(
                BigDecimal("12"),
                AssetId.global(UnitId.of("jpy")),
                jpyDescriptor,
            ),
        )
        assertEquals(
            AtomicAmount(1234, usd),
            AtomicAmount.fromMajorUnits(BigDecimal("12.34"), usd, usdDescriptor),
        )
        assertEquals(
            1234L,
            AtomicAmount.fromMajorUnits(
                BigDecimal("1.234"),
                AssetId.global(UnitId.of("bhd")),
                bhdDescriptor,
            ).value,
        )
        assertEquals(
            1L,
            AtomicAmount.fromMajorUnits(
                BigDecimal("0.00000001"),
                AssetId.global(UnitId.BTC),
                btcDescriptor,
            ).value,
        )
    }

    @Test
    fun `custom units default to atomic integers and can opt into display precision`() {
        val unit = UnitId.of("points")
        val asset = AssetId.mintScoped(unit, "https://mint.example")
        val defaultDescriptor = UnitDescriptor.defaultFor(unit)
        val decimalDescriptor = UnitDescriptor.custom(unit, fractionDigits = 3)

        assertEquals(UnitKind.CUSTOM, defaultDescriptor.kind)
        assertEquals(0, defaultDescriptor.fractionDigits)
        assertThrows(ArithmeticException::class.java) {
            AtomicAmount.fromMajorUnits(BigDecimal("1.5"), asset, defaultDescriptor)
        }
        assertEquals(
            AtomicAmount(1500, asset),
            AtomicAmount.fromMajorUnits(BigDecimal("1.500"), asset, decimalDescriptor),
        )
    }

    @Test
    fun `ulong conversion refuses values outside signed storage range`() {
        assertEquals(
            AtomicAmount(Long.MAX_VALUE, sat),
            AtomicAmount.fromULong(Long.MAX_VALUE.toULong(), sat),
        )
        assertThrows(IllegalArgumentException::class.java) {
            AtomicAmount.fromULong(Long.MAX_VALUE.toULong() + 1u, sat)
        }
    }
}
