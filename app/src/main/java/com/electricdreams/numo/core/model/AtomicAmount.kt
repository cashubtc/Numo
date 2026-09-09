package com.electricdreams.numo.core.model

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Economic identity of a unit.
 *
 * Standard units can use [global]. Unknown custom units should use [mintScoped] until the user has
 * explicitly declared multiple issuers fungible. This prevents identically named custom units from
 * unrelated mints from being added together.
 */
data class AssetId(
    val unit: UnitId,
    val issuerScope: String? = null,
) {
    init {
        require(issuerScope == null || issuerScope.isNotBlank()) {
            "Issuer scope cannot be blank"
        }
    }

    companion object {
        @JvmStatic
        fun global(unit: UnitId): AssetId = AssetId(unit)

        @JvmStatic
        fun mintScoped(unit: UnitId, canonicalMintUrl: String): AssetId =
            AssetId(unit, canonicalMintUrl.trim().also {
                require(it.isNotEmpty()) { "Mint URL cannot be blank" }
            })
    }
}

/**
 * A non-negative quantity in the atomic denomination of [asset].
 *
 * Arithmetic is checked for both unit mismatches and [Long] overflow. Conversion to another asset
 * must go through a conversion quote; changing [asset] directly is intentionally unsupported.
 */
data class AtomicAmount(
    val value: Long,
    val asset: AssetId,
) : Comparable<AtomicAmount> {
    init {
        require(value >= 0) { "Atomic amount cannot be negative" }
    }

    val unit: UnitId
        get() = asset.unit

    operator fun plus(other: AtomicAmount): AtomicAmount {
        requireSameAsset(other)
        return copy(value = Math.addExact(value, other.value))
    }

    operator fun minus(other: AtomicAmount): AtomicAmount {
        requireSameAsset(other)
        require(value >= other.value) { "Atomic amount cannot become negative" }
        return copy(value = Math.subtractExact(value, other.value))
    }

    operator fun times(quantity: Int): AtomicAmount {
        require(quantity >= 0) { "Quantity cannot be negative" }
        return copy(value = Math.multiplyExact(value, quantity.toLong()))
    }

    override fun compareTo(other: AtomicAmount): Int {
        requireSameAsset(other)
        return value.compareTo(other.value)
    }

    fun toMajorUnits(descriptor: UnitDescriptor): BigDecimal {
        requireDescriptor(descriptor)
        return BigDecimal.valueOf(value).movePointLeft(descriptor.fractionDigits)
    }

    private fun requireSameAsset(other: AtomicAmount) {
        require(asset == other.asset) {
            "Cannot combine amounts in $asset and ${other.asset}"
        }
    }

    private fun requireDescriptor(descriptor: UnitDescriptor) {
        require(unit == descriptor.unit) {
            "Descriptor unit ${descriptor.unit} does not match amount unit $unit"
        }
    }

    companion object {
        @JvmStatic
        fun zero(asset: AssetId): AtomicAmount = AtomicAmount(0, asset)

        /**
         * Convert an exact major-unit input into its atomic representation.
         *
         * Inputs with more precision than the descriptor permits are rejected rather than rounded.
         * Conversion quotes that intentionally round must do so explicitly before calling here.
         */
        @JvmStatic
        fun fromMajorUnits(
            value: BigDecimal,
            asset: AssetId,
            descriptor: UnitDescriptor,
        ): AtomicAmount {
            require(value.signum() >= 0) { "Amount cannot be negative" }
            require(asset.unit == descriptor.unit) {
                "Descriptor unit ${descriptor.unit} does not match asset unit ${asset.unit}"
            }

            val atomicValue = value
                .movePointRight(descriptor.fractionDigits)
                .setScale(0, RoundingMode.UNNECESSARY)
                .longValueExact()
            return AtomicAmount(atomicValue, asset)
        }
    }
}
