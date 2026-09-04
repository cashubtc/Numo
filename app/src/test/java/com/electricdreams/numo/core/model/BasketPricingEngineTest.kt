package com.electricdreams.numo.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class BasketPricingEngineTest {

    private val sat = AssetId.global(UnitId.SAT)
    private val usd = AssetId.global(UnitId.of("usd"))

    @Test
    fun `single unit basket remains exact without conversion`() {
        val points = AssetId.mintScoped(UnitId.of("points"), "https://mint.example")
        val result = BasketPricingEngine(emptyList()).normalize(
            lines = listOf(
                BasketPriceLine("one", AtomicAmount(40L, points)),
                BasketPriceLine("two", AtomicAmount(2L, points)),
            ),
            target = points,
        )

        val chargeable = result as BasketNormalizationResult.Chargeable
        assertEquals(42L, chargeable.amount.value)
        assertEquals(points, chargeable.amount.asset)
        assertTrue(chargeable.components.all { it.path.isEmpty() })
    }

    @Test
    fun `mixed fiat and sat basket can normalize in either direction`() {
        val rates = UnitConversionRate.fromBitcoinPrice(
            fiatUnit = UnitId.of("usd"),
            fiatPerBitcoin = BigDecimal("50000"),
        )
        val lines = listOf(
            BasketPriceLine("coffee", AtomicAmount(5_000L, usd)),
            BasketPriceLine("tip", AtomicAmount(100_000L, sat)),
        )
        val engine = BasketPricingEngine(rates)

        val inSats = engine.normalize(lines, sat) as BasketNormalizationResult.Chargeable
        val inUsd = engine.normalize(lines, usd) as BasketNormalizationResult.Chargeable

        assertEquals(200_000L, inSats.amount.value)
        assertEquals(10_000L, inUsd.amount.value)
    }

    @Test
    fun `conversion rounds once after grouping each source asset`() {
        val target = AssetId.global(UnitId.of("token"))
        val rate = UnitConversionRate(sat, target, BigDecimal("0.5"))
        val lines = listOf(
            BasketPriceLine("a", AtomicAmount(1L, sat)),
            BasketPriceLine("b", AtomicAmount(1L, sat)),
        )

        val result = BasketPricingEngine(listOf(rate)).normalize(lines, target)
            as BasketNormalizationResult.Chargeable

        assertEquals(1L, result.amount.value)
        assertEquals(1, result.components.size)
    }

    @Test
    fun `custom unit without an explicit conversion is rejected`() {
        val points = AssetId.global(UnitId.of("points"))
        val result = BasketPricingEngine(emptyList()).normalize(
            listOf(
                BasketPriceLine("points", AtomicAmount(10L, points)),
                BasketPriceLine("sats", AtomicAmount(10L, sat)),
            ),
            points,
        )

        val unsupported = result as BasketNormalizationResult.Unsupported
        assertEquals(
            BasketNormalizationFailureReason.MISSING_CONVERSION,
            unsupported.failures.single().reason,
        )
        assertEquals(sat, unsupported.failures.single().source)
    }

    @Test
    fun `expired conversion is distinguished from a missing conversion`() {
        val rate = UnitConversionRate(
            source = sat,
            target = usd,
            targetAtomicPerSourceAtomic = BigDecimal.ONE,
            expiresAtMillis = 99L,
        )
        val result = BasketPricingEngine(listOf(rate), nowMillis = 100L).normalize(
            listOf(BasketPriceLine("line", AtomicAmount(10L, sat))),
            usd,
        )

        val unsupported = result as BasketNormalizationResult.Unsupported
        assertEquals(
            BasketNormalizationFailureReason.STALE_CONVERSION,
            unsupported.failures.single().reason,
        )
    }

    @Test
    fun `same custom unit label from another issuer is not fungible`() {
        val issuerA = AssetId.mintScoped(UnitId.of("points"), "https://a.example")
        val issuerB = AssetId.mintScoped(UnitId.of("points"), "https://b.example")
        val result = BasketPricingEngine(emptyList()).normalize(
            listOf(BasketPriceLine("line", AtomicAmount(10L, issuerA))),
            issuerB,
        )

        assertTrue(result is BasketNormalizationResult.Unsupported)
    }

    @Test
    fun `explicit multi-hop conversion is supported`() {
        val eur = AssetId.global(UnitId.of("eur"))
        val result = BasketPricingEngine(
            listOf(
                UnitConversionRate(sat, usd, BigDecimal("2")),
                UnitConversionRate(usd, eur, BigDecimal("3")),
            ),
        ).normalize(
            listOf(BasketPriceLine("line", AtomicAmount(5L, sat))),
            eur,
        ) as BasketNormalizationResult.Chargeable

        assertEquals(30L, result.amount.value)
        assertEquals(2, result.components.single().path.size)
    }

    @Test
    fun `overflow returns arithmetic failure instead of wrapping`() {
        val result = BasketPricingEngine(emptyList()).normalize(
            listOf(
                BasketPriceLine("a", AtomicAmount(Long.MAX_VALUE, sat)),
                BasketPriceLine("b", AtomicAmount(1L, sat)),
            ),
            sat,
        )

        assertTrue(result is BasketNormalizationResult.ArithmeticFailure)
    }

    @Test
    fun `chargeable targets exclude unsupported candidates`() {
        val points = AssetId.global(UnitId.of("points"))
        val results = BasketPricingEngine(emptyList()).chargeableTargets(
            lines = listOf(BasketPriceLine("line", AtomicAmount(1L, points))),
            candidates = listOf(sat, points),
        )

        assertEquals(listOf(points), results.map { it.amount.asset })
    }
}
