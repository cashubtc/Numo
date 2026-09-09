package com.electricdreams.numo.core.model

import com.google.gson.annotations.SerializedName
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Immutable snapshot of an item in the checkout basket.
 * Used for persisting the checkout basket with payment history.
 * 
 * This captures all the information needed to reconstruct
 * a receipt view showing what items were purchased.
 */
data class CheckoutBasketItem(
    /** Item unique identifier */
    @SerializedName("itemId")
    val itemId: String,

    /** Item UUID for internal tracking */
    @SerializedName("uuid")
    val uuid: String,

    /** Display name of the item */
    @SerializedName("name")
    val name: String,

    /** Optional variation name (e.g., "Large", "Extra Shot") */
    @SerializedName("variationName")
    val variationName: String? = null,

    /** SKU if available */
    @SerializedName("sku")
    val sku: String? = null,

    /** Category of the item */
    @SerializedName("category")
    val category: String? = null,

    /** Quantity purchased */
    @SerializedName("quantity")
    val quantity: Int,

    /** Price type: "FIAT" or "SATS" */
    @SerializedName("priceType")
    val priceType: String,

    /** NET price per unit in fiat (excluding VAT) - in minor units (cents) */
    @SerializedName("netPriceCents")
    val netPriceCents: Long,

    /** Price per unit in sats (if priceType is SATS) */
    @SerializedName("priceSats")
    val priceSats: Long,

    /** Currency code for fiat pricing (e.g., "USD", "EUR") - now tied to global fiat setting */
    @SerializedName("priceCurrency")
    val priceCurrency: String,

    /** Whether VAT applies to this item */
    @SerializedName("vatEnabled")
    val vatEnabled: Boolean,

    /** VAT rate as integer percentage (e.g., 20 for 20%) */
    @SerializedName("vatRate")
    val vatRate: Int,

    /** Canonical unit of [netPriceAtomic]. Null only in legacy receipt snapshots. */
    @SerializedName("priceUnit")
    val priceUnit: String? = null,

    /** NET price per unit in the unit's atomic denomination. */
    @SerializedName("netPriceAtomic")
    val netPriceAtomic: Long? = null,

    /** GROSS price per unit in the same atomic denomination. */
    @SerializedName("grossPriceAtomic")
    val grossPriceAtomic: Long? = null,

    /** Issuer scope for non-fungible custom units. */
    @SerializedName("priceIssuerScope")
    val priceIssuerScope: String? = null,
) {

    /**
     * Get the display name including variation if present.
     */
    val displayName: String
        get() = if (!variationName.isNullOrEmpty()) {
            "$name - $variationName"
        } else {
            name
        }

    /**
     * Calculate the net total (quantity × net price) in minor units.
     */
    fun getNetTotalCents(): Long = Math.multiplyExact(netPriceCents, quantity.toLong())

    /**
     * Calculate the net total in sats (for sats-priced items).
     */
    fun getNetTotalSats(): Long = Math.multiplyExact(priceSats, quantity.toLong())

    /**
     * Calculate VAT amount per unit in minor units.
     */
    fun getVatPerUnitCents(): Long {
        if (!vatEnabled || vatRate <= 0 || priceType != "FIAT") return 0L
        return BigDecimal.valueOf(netPriceCents)
            .multiply(BigDecimal.valueOf(vatRate.toLong()))
            .divide(BigDecimal.valueOf(100L), 0, RoundingMode.DOWN)
            .longValueExact()
    }

    /**
     * Calculate total VAT amount for this line item in minor units.
     */
    fun getTotalVatCents(): Long = Math.multiplyExact(getVatPerUnitCents(), quantity.toLong())

    /**
     * Calculate gross price per unit (including VAT) in minor units.
     */
    fun getGrossPricePerUnitCents(): Long = Math.addExact(netPriceCents, getVatPerUnitCents())

    /**
     * Calculate gross total (including VAT) in minor units.
     */
    fun getGrossTotalCents(): Long =
        Math.multiplyExact(getGrossPricePerUnitCents(), quantity.toLong())

    /**
     * Check if this item is priced in sats.
     */
    fun isSatsPrice(): Boolean = priceType == "SATS"

    /**
     * Check if this item is priced in fiat.
     */
    fun isFiatPrice(): Boolean = priceType == "FIAT"

    /** Resolve this snapshot's unit-bearing NET amount, including legacy receipts. */
    fun getNetAtomicAmount(): AtomicAmount {
        val unit = if (priceUnit == null) {
            if (isSatsPrice()) UnitId.SAT else UnitId.of(priceCurrency)
        } else {
            requireNotNull(UnitId.ofOrNull(priceUnit)?.takeUnless { it.isReserved }) {
                "Invalid checkout item unit: $priceUnit"
            }
        }
        val asset = priceIssuerScope?.takeIf {
            it.isNotBlank() && UnitDescriptor.defaultFor(unit).kind == UnitKind.CUSTOM
        }?.let {
            AssetId.mintScoped(unit, it)
        } ?: AssetId.global(unit)
        val value = netPriceAtomic ?: if (isSatsPrice()) {
            priceSats
        } else {
            legacyFiatAtomicValue(netPriceCents, unit)
        }
        return AtomicAmount(value, asset)
    }

    fun getGrossAtomicAmount(): AtomicAmount {
        val net = getNetAtomicAmount()
        val explicitGross = grossPriceAtomic
        if (explicitGross != null) return AtomicAmount(explicitGross, net.asset)
        if (netPriceAtomic == null && isFiatPrice()) {
            // Preserve the receipt's original VAT calculation before changing denomination.
            return AtomicAmount(
                legacyFiatAtomicValue(getGrossPricePerUnitCents(), net.unit),
                net.asset,
            )
        }
        if (!vatEnabled || vatRate <= 0) return net
        val gross = BigDecimal.valueOf(net.value)
            .multiply(BigDecimal.valueOf(100L + vatRate.toLong()))
            .divide(
                BigDecimal.valueOf(100L),
                0,
                RoundingMode.HALF_UP,
            )
            .longValueExact()
        return AtomicAmount(gross, net.asset)
    }

    fun getNetLineAtomicAmount(): AtomicAmount = getNetAtomicAmount() * quantity

    fun getGrossLineAtomicAmount(): AtomicAmount = getGrossAtomicAmount() * quantity

    private fun legacyFiatAtomicValue(cents: Long, unit: UnitId): Long =
        BigDecimal.valueOf(cents, 2)
            .movePointRight(UnitDescriptor.defaultFor(unit).fractionDigits)
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact()

    companion object {
        /**
         * Create a CheckoutBasketItem from a BasketItem snapshot.
         */
        fun fromBasketItem(
            basketItem: BasketItem,
            currencyCode: String
        ): CheckoutBasketItem {
            val item = basketItem.item
            val atomicPrice = item.getNetAtomicAmount(currencyCode)
            return CheckoutBasketItem(
                itemId = item.id ?: "",
                uuid = item.uuid,
                name = item.name ?: "Unknown Item",
                variationName = item.variationName,
                sku = item.sku,
                category = item.category,
                quantity = basketItem.quantity,
                priceType = item.priceType.name,
                // Retained for old receipt renderers; unit-aware readers use netPriceAtomic.
                netPriceCents = (item.price * 100).toLong(),
                priceSats = item.priceSats,
                priceCurrency = currencyCode,
                vatEnabled = item.vatEnabled,
                vatRate = item.vatRate,
                priceUnit = atomicPrice.unit.value,
                netPriceAtomic = atomicPrice.value,
                grossPriceAtomic = item.getGrossAtomicAmount(currencyCode).value,
                priceIssuerScope = atomicPrice.asset.issuerScope,
            )
        }
    }
}
