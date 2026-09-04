package com.electricdreams.numo.core.model

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.ArrayDeque

/** A directional conversion rate expressed in atomic source and target denominations. */
data class UnitConversionRate(
    val source: AssetId,
    val target: AssetId,
    val targetAtomicPerSourceAtomic: BigDecimal,
    val expiresAtMillis: Long? = null,
    val provider: String? = null,
) {
    init {
        require(source != target) { "A conversion rate must change the asset" }
        require(targetAtomicPerSourceAtomic.signum() > 0) {
            "A conversion rate must be positive"
        }
    }

    fun isUsableAt(nowMillis: Long): Boolean =
        expiresAtMillis == null || nowMillis <= expiresAtMillis

    fun inverse(): UnitConversionRate = UnitConversionRate(
        source = target,
        target = source,
        targetAtomicPerSourceAtomic = BigDecimal.ONE.divide(
            targetAtomicPerSourceAtomic,
            CONVERSION_SCALE,
            RoundingMode.HALF_EVEN,
        ),
        expiresAtMillis = expiresAtMillis,
        provider = provider,
    )

    companion object {
        private const val CONVERSION_SCALE = 30

        /**
         * Build the two conversion directions implied by a BTC price in a fiat unit.
         */
        @JvmStatic
        fun fromBitcoinPrice(
            fiatUnit: UnitId,
            fiatPerBitcoin: BigDecimal,
            expiresAtMillis: Long? = null,
            provider: String? = null,
        ): List<UnitConversionRate> {
            require(fiatPerBitcoin.signum() > 0) { "Bitcoin price must be positive" }
            val fiatDescriptor = UnitDescriptor.defaultFor(fiatUnit)
            require(
                !fiatUnit.isReserved &&
                    fiatUnit != UnitId.SAT &&
                    fiatUnit != UnitId.MSAT &&
                    fiatUnit != UnitId.BTC,
            ) {
                "Bitcoin price conversion requires a non-Bitcoin target unit"
            }

            val fiatAtomicPerSat = fiatPerBitcoin
                .movePointRight(fiatDescriptor.fractionDigits)
                .divide(BigDecimal.valueOf(SATS_PER_BITCOIN), CONVERSION_SCALE, RoundingMode.HALF_EVEN)
            val satToFiat = UnitConversionRate(
                source = AssetId.global(UnitId.SAT),
                target = AssetId.global(fiatUnit),
                targetAtomicPerSourceAtomic = fiatAtomicPerSat,
                expiresAtMillis = expiresAtMillis,
                provider = provider,
            )
            return listOf(satToFiat, satToFiat.inverse())
        }

        private const val SATS_PER_BITCOIN = 100_000_000L
    }
}

data class BasketPriceLine(
    val reference: String,
    val amount: AtomicAmount,
)

enum class BasketNormalizationFailureReason {
    MISSING_CONVERSION,
    STALE_CONVERSION,
}

data class BasketNormalizationFailure(
    val source: AssetId,
    val reason: BasketNormalizationFailureReason,
)

data class AppliedBasketConversion(
    val sourceAmount: AtomicAmount,
    val targetAmount: AtomicAmount,
    val path: List<UnitConversionRate>,
)

sealed interface BasketNormalizationResult {
    data object Empty : BasketNormalizationResult

    data class Chargeable(
        val amount: AtomicAmount,
        val components: List<AppliedBasketConversion>,
    ) : BasketNormalizationResult

    data class Unsupported(
        val target: AssetId,
        val failures: List<BasketNormalizationFailure>,
    ) : BasketNormalizationResult

    data class ArithmeticFailure(
        val target: AssetId,
        val message: String,
    ) : BasketNormalizationResult
}

/**
 * Normalizes a basket into an explicitly selected charge asset.
 *
 * Amounts are grouped by economic asset before conversion, then rounded once per source asset.
 * A conversion path only exists when every edge was explicitly supplied; matching unit labels are
 * never treated as proof of fungibility.
 */
class BasketPricingEngine(
    private val rates: List<UnitConversionRate>,
    private val nowMillis: Long = System.currentTimeMillis(),
    private val roundingMode: RoundingMode = RoundingMode.HALF_UP,
) {

    fun normalize(
        lines: List<BasketPriceLine>,
        target: AssetId,
    ): BasketNormalizationResult {
        if (lines.isEmpty()) return BasketNormalizationResult.Empty

        val grouped = try {
            groupLines(lines)
        } catch (e: ArithmeticException) {
            return BasketNormalizationResult.ArithmeticFailure(
                target = target,
                message = e.message ?: "Basket amount overflow",
            )
        }

        val usableRates = rates.filter { it.isUsableAt(nowMillis) }
        val failures = grouped.keys
            .filter { it != target && grouped.getValue(it).value != 0L }
            .mapNotNull { source ->
                if (findPath(source, target, usableRates) != null) {
                    null
                } else {
                    val reason = if (findPath(source, target, rates) != null) {
                        BasketNormalizationFailureReason.STALE_CONVERSION
                    } else {
                        BasketNormalizationFailureReason.MISSING_CONVERSION
                    }
                    BasketNormalizationFailure(source, reason)
                }
            }
        if (failures.isNotEmpty()) {
            return BasketNormalizationResult.Unsupported(target, failures)
        }

        return try {
            val components = grouped.values.map { sourceAmount ->
                val path = if (sourceAmount.asset == target || sourceAmount.value == 0L) {
                    emptyList()
                } else {
                    checkNotNull(findPath(sourceAmount.asset, target, usableRates))
                }
                val targetAmount = applyPath(sourceAmount, target, path)
                AppliedBasketConversion(sourceAmount, targetAmount, path)
            }
            val total = components.fold(AtomicAmount.zero(target)) { sum, component ->
                sum + component.targetAmount
            }
            BasketNormalizationResult.Chargeable(total, components)
        } catch (e: ArithmeticException) {
            BasketNormalizationResult.ArithmeticFailure(
                target = target,
                message = e.message ?: "Converted amount overflow",
            )
        }
    }

    fun chargeableTargets(
        lines: List<BasketPriceLine>,
        candidates: Collection<AssetId>,
    ): List<BasketNormalizationResult.Chargeable> {
        return candidates.distinct().mapNotNull { candidate ->
            normalize(lines, candidate) as? BasketNormalizationResult.Chargeable
        }
    }

    private fun groupLines(lines: List<BasketPriceLine>): LinkedHashMap<AssetId, AtomicAmount> {
        val grouped = linkedMapOf<AssetId, AtomicAmount>()
        lines.forEach { line ->
            val current = grouped[line.amount.asset] ?: AtomicAmount.zero(line.amount.asset)
            grouped[line.amount.asset] = current + line.amount
        }
        return grouped
    }

    private fun applyPath(
        sourceAmount: AtomicAmount,
        target: AssetId,
        path: List<UnitConversionRate>,
    ): AtomicAmount {
        if (path.isEmpty()) {
            require(sourceAmount.asset == target || sourceAmount.value == 0L) {
                "A non-zero amount requires a conversion path"
            }
            return AtomicAmount(sourceAmount.value, target)
        }

        val combinedRate = path.fold(BigDecimal.ONE) { product, edge ->
            product.multiply(edge.targetAtomicPerSourceAtomic)
        }
        val convertedValue = BigDecimal.valueOf(sourceAmount.value)
            .multiply(combinedRate)
            .setScale(0, roundingMode)
            .longValueExact()
        return AtomicAmount(convertedValue, target)
    }

    private fun findPath(
        source: AssetId,
        target: AssetId,
        candidateRates: List<UnitConversionRate>,
    ): List<UnitConversionRate>? {
        if (source == target) return emptyList()

        data class PendingPath(
            val asset: AssetId,
            val path: List<UnitConversionRate>,
        )

        val outgoing = candidateRates.groupBy { it.source }
        val visited = mutableSetOf(source)
        val queue = ArrayDeque<PendingPath>()
        queue.add(PendingPath(source, emptyList()))

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            outgoing[current.asset].orEmpty().forEach { edge ->
                val nextPath = current.path + edge
                if (edge.target == target) return nextPath
                if (visited.add(edge.target)) {
                    queue.add(PendingPath(edge.target, nextPath))
                }
            }
        }
        return null
    }
}
