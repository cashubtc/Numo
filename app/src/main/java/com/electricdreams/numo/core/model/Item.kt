package com.electricdreams.numo.core.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/**
 * Enum representing the price type for an item.
 */
enum class PriceType {
    FIAT,   // Price in fiat currency (e.g., USD, EUR)
    SATS    // Price in satoshis (Bitcoin)
}

/**
 * Model class for an item in the merchant's catalog.
 *
 * Supports both fiat pricing and Bitcoin (sats) pricing.
 * 
 * VAT/Tax Handling:
 * - `price` always stores the NET price (excluding VAT)
 * - When vatEnabled=true, use getGrossPrice() for customer-facing prices
 * - This follows EU/UK regulations where merchants must track net prices
 * - Common VAT rates: EU (19-25%), UK (20%), US (0-10% sales tax)
 */
@Parcelize
data class Item(
    var id: String? = null,                     // Legacy ID (kept for backwards compatibility)
    var uuid: String = UUID.randomUUID().toString(), // Internal UUID for tracking across app
    var name: String? = null,                   // Item name
    var variationName: String? = null,          // Optional variation name
    var sku: String? = null,                    // Stock keeping unit
    var description: String? = null,            // Item description
    var category: String? = null,               // Category
    var gtin: String? = null,                   // Global Trade Item Number
    var price: Double = 0.0,                    // NET price in fiat (excluding VAT)
    var priceSats: Long = 0L,                   // Price in satoshis (used when priceType is SATS)
    var priceType: PriceType = PriceType.FIAT,  // Whether price is in fiat or sats (fiat unit is global)
    var vatEnabled: Boolean = false,            // Whether VAT/tax applies to this item
    var vatRate: Int = 0,                       // VAT rate as integer percentage (e.g., 20 for 20%)
    var trackInventory: Boolean = false,        // Whether to track inventory for this item
    var quantity: Int = 0,                      // Available quantity (only used if trackInventory is true)
    var alertEnabled: Boolean = false,          // Whether stock alerts are enabled
    var alertThreshold: Int = 0,                // Threshold for stock alerts
    var imagePath: String? = null,              // Path to item image (can be null)
    /** Canonical Cashu unit identifier. Null only for records created before unit migration. */
    var priceUnit: String? = null,
    /** NET price in the unit's atomic denomination. Null only for legacy records. */
    var priceAtomic: Long? = null,
    /** Stored GROSS price when rounding cannot be reconstructed from NET plus VAT. */
    var grossPriceAtomic: Long? = null,
    /** Mint URL for a custom unit that is not known to be fungible across issuers. */
    var priceIssuerScope: String? = null,
) : Parcelable {

    /**
     * Get display name combining name and variation if available.
     */
    val displayName: String
        get() = if (!variationName.isNullOrEmpty()) {
            "${name ?: ""} - $variationName"
        } else {
            name.orEmpty()
        }

    /**
     * Get the net price (excluding VAT).
     * For fiat: returns the stored price
     * For sats: returns 0.0 (use getNetSats() instead)
     */
    fun getNetPrice(): Double {
        return if (priceType == PriceType.FIAT) price else 0.0
    }

    /**
     * Get the net price in sats (excluding VAT).
     * For sats: returns the stored priceSats
     * For fiat: returns 0L (use getNetPrice() instead)
     */
    fun getNetSats(): Long {
        return if (priceType == PriceType.SATS) priceSats else 0L
    }

    /**
     * Get the VAT amount for this item.
     * Returns 0.0 for fiat or 0L for sats if VAT is not enabled.
     */
    fun getVatAmount(): Double {
        if (!vatEnabled || priceType != PriceType.FIAT || vatRate <= 0) return 0.0
        return price * (vatRate / 100.0)
    }

    /**
     * Get the VAT amount in sats for this item.
     * Returns 0L if VAT is not enabled or price type is not SATS.
     */
    fun getVatSats(): Long {
        if (!vatEnabled || priceType != PriceType.SATS || vatRate <= 0) return 0L
        return (priceSats * vatRate / 100.0).toLong()
    }

    /**
     * Get the gross price (including VAT).
     * For fiat: returns net price + VAT
     * For sats: returns 0.0 (use getGrossSats() instead)
     */
    fun getGrossPrice(): Double {
        if (priceType != PriceType.FIAT) return 0.0
        return price + getVatAmount()
    }

    /**
     * Get the gross price in sats (including VAT).
     * For sats: returns net sats + VAT sats
     * For fiat: returns 0L (use getGrossPrice() instead)
     */
    fun getGrossSats(): Long {
        if (priceType != PriceType.SATS) return 0L
        return priceSats + getVatSats()
    }

    /**
     * Resolve the economic asset represented by this item.
     *
     * [legacyFiatUnit] is used only for records saved before per-item units existed. It must be
     * supplied by the caller so changing the global display preference can never relabel an
     * already explicit item price.
     */
    fun resolvePriceAsset(legacyFiatUnit: String): AssetId {
        val explicitUnit = UnitId.ofOrNull(priceUnit)?.takeUnless { it.isReserved }
        val unit = explicitUnit ?: when (priceType) {
            PriceType.SATS -> UnitId.SAT
            PriceType.FIAT -> UnitId.of(legacyFiatUnit)
        }
        val issuer = priceIssuerScope
            ?.trim()
            ?.takeIf {
                it.isNotEmpty() && UnitDescriptor.defaultFor(unit).kind == UnitKind.CUSTOM
            }
        return if (issuer == null) AssetId.global(unit) else AssetId.mintScoped(unit, issuer)
    }

    /** Return the NET price as a checked, unit-bearing atomic amount. */
    fun getNetAtomicAmount(legacyFiatUnit: String): AtomicAmount {
        val asset = resolvePriceAsset(legacyFiatUnit)
        val explicitValue = priceAtomic
        if (explicitValue != null) {
            require(explicitValue >= 0) { "Item price cannot be negative" }
            return AtomicAmount(explicitValue, asset)
        }

        return when (priceType) {
            PriceType.SATS -> AtomicAmount(priceSats, asset)
            PriceType.FIAT -> {
                val descriptor = UnitDescriptor.defaultFor(asset.unit)
                val atomicValue = BigDecimal.valueOf(price)
                    .movePointRight(descriptor.fractionDigits)
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact()
                AtomicAmount(atomicValue, asset)
            }
        }
    }

    /** Return the customer-facing price including VAT in the same asset as the NET price. */
    fun getGrossAtomicAmount(legacyFiatUnit: String): AtomicAmount {
        val net = getNetAtomicAmount(legacyFiatUnit)
        if (!vatEnabled || vatRate <= 0) return net

        grossPriceAtomic?.takeIf { it >= net.value }?.let { storedGross ->
            return AtomicAmount(storedGross, net.asset)
        }

        // Legacy fiat prices retained fractional cents after removing VAT. Round the original
        // gross price only once, rather than applying VAT to an already rounded net price.
        if (priceAtomic == null) {
            val grossValue = when (priceType) {
                PriceType.FIAT -> BigDecimal.valueOf(price)
                    .multiply(BigDecimal.valueOf(100L + vatRate.toLong()))
                    .movePointLeft(2)
                    .movePointRight(UnitDescriptor.defaultFor(net.unit).fractionDigits)
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact()
                PriceType.SATS -> BigDecimal.valueOf(priceSats)
                    .multiply(BigDecimal.valueOf(100L + vatRate.toLong()))
                    .divide(BigDecimal.valueOf(100L), 0, RoundingMode.DOWN)
                    .longValueExact()
            }
            return AtomicAmount(grossValue, net.asset)
        }

        val grossValue = BigDecimal.valueOf(net.value)
            .multiply(BigDecimal.valueOf(100L + vatRate.toLong()))
            .divide(BigDecimal.valueOf(100L), 0, RoundingMode.HALF_UP)
            .longValueExact()
        return AtomicAmount(grossValue, net.asset)
    }

    /**
     * Materialize legacy pricing fields into the unit-aware schema.
     *
     * Returns true when the item changed and should be persisted.
     */
    fun ensureExplicitPrice(legacyFiatUnit: String): Boolean {
        val canonicalExplicitUnit = UnitId.ofOrNull(priceUnit)?.takeUnless { it.isReserved }
        val validExplicitAmount = priceAtomic?.takeIf { it >= 0 }
        if (canonicalExplicitUnit != null && validExplicitAmount != null) {
            val canonicalIssuer = priceIssuerScope
                ?.trim()
                ?.takeIf {
                    it.isNotEmpty() &&
                        UnitDescriptor.defaultFor(canonicalExplicitUnit).kind == UnitKind.CUSTOM
                }
            var changed = priceUnit != canonicalExplicitUnit.value ||
                priceIssuerScope != canonicalIssuer
            if (grossPriceAtomic?.let { it < validExplicitAmount } == true) {
                grossPriceAtomic = null
                changed = true
            }
            priceUnit = canonicalExplicitUnit.value
            priceIssuerScope = canonicalIssuer
            return changed
        }

        // Invalid or partial explicit data is not trusted. Derive both fields from the complete
        // legacy representation so a corrupted unit cannot be paired with an unrelated amount.
        priceUnit = null
        priceAtomic = null
        grossPriceAtomic = null
        priceIssuerScope = null
        val migrated = getNetAtomicAmount(legacyFiatUnit)
        val migratedGross = getGrossAtomicAmount(legacyFiatUnit)
        priceUnit = migrated.unit.value
        priceAtomic = migrated.value
        grossPriceAtomic = migratedGross.value
        return true
    }

    /** Format the customer-facing gross price in the item’s own unit. */
    fun getFormattedPrice(currencyCode: String): String {
        val amount = getGrossAtomicAmount(currencyCode)
        return UnitAmountFormatter.format(amount, UnitDescriptor.defaultFor(amount.unit))
    }

    /**
     * Get formatted net price (excluding VAT).
     */
    fun getFormattedNetPrice(currencyCode: String): String {
        val amount = getNetAtomicAmount(currencyCode)
        return UnitAmountFormatter.format(amount, UnitDescriptor.defaultFor(amount.unit))
    }

    /**
     * Get formatted VAT amount.
     */
    fun getFormattedVatAmount(currencyCode: String): String {
        if (!vatEnabled) return ""
        val net = getNetAtomicAmount(currencyCode)
        val gross = getGrossAtomicAmount(currencyCode)
        return UnitAmountFormatter.formatAtomic(
            value = Math.subtractExact(gross.value, net.value),
            descriptor = UnitDescriptor.defaultFor(net.unit),
        )
    }

    /**
     * Get formatted gross price (including VAT).
     */
    fun getFormattedGrossPrice(currencyCode: String): String {
        return getFormattedPrice(currencyCode)
    }

    // Java interop helper for isAlertEnabled() to match original Java API
    fun isAlertEnabled(): Boolean = alertEnabled
    
    // Java interop helper for trackInventory
    fun isTrackInventory(): Boolean = trackInventory

    companion object {
        /**
         * Common VAT/tax rates used worldwide.
         * Format: Pair(displayName, rate)
         */
        val COMMON_VAT_RATES = listOf(
            Pair("0%", 0.0),
            Pair("5%", 5.0),
            Pair("7%", 7.0),
            Pair("10%", 10.0),
            Pair("19%", 19.0),    // Germany
            Pair("20%", 20.0),    // UK, France
            Pair("21%", 21.0),    // Spain, Belgium
            Pair("23%", 23.0),    // Ireland, Poland
            Pair("25%", 25.0),    // Sweden, Denmark
        )

        /**
         * Calculate net price from gross price and VAT rate.
         * Formula: net = gross / (1 + rate/100)
         */
        fun calculateNetFromGross(grossPrice: Double, vatRate: Double): Double {
            if (vatRate <= 0) return grossPrice
            return grossPrice / (1 + vatRate / 100.0)
        }

        /**
         * Calculate gross price from net price and VAT rate.
         * Formula: gross = net * (1 + rate/100)
         */
        fun calculateGrossFromNet(netPrice: Double, vatRate: Double): Double {
            if (vatRate <= 0) return netPrice
            return netPrice * (1 + vatRate / 100.0)
        }
    }
}
