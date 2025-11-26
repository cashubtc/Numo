package com.electricdreams.shellshock.core.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.util.UUID
import kotlin.math.roundToLong

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
    var priceType: PriceType = PriceType.FIAT,  // Whether price is in fiat or sats
    var priceCurrency: String = "USD",          // Fiat currency code (USD, EUR, etc.)
    var vatEnabled: Boolean = false,            // Whether VAT/tax applies to this item
    var vatRate: Int = 0,                       // VAT rate as integer percentage (e.g., 20 for 20%)
    var trackInventory: Boolean = false,        // Whether to track inventory for this item
    var quantity: Int = 0,                      // Available quantity (only used if trackInventory is true)
    var alertEnabled: Boolean = false,          // Whether stock alerts are enabled
    var alertThreshold: Int = 0,                // Threshold for stock alerts
    var imagePath: String? = null,              // Path to item image (can be null)
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
     * Get formatted price string for customer display (includes VAT if applicable).
     * Uses the Amount class for consistent currency-aware formatting:
     * - USD, GBP: period decimal (e.g., $4.20, £4.20)
     * - EUR: comma decimal (e.g., €4,20)
     * - JPY: no decimals (e.g., ¥420)
     * - BTC: comma thousand separator (e.g., ₿1,000)
     */
    fun getFormattedPrice(currencySymbol: String = "$"): String {
        return when (priceType) {
            PriceType.SATS -> Amount(priceSats, Amount.Currency.BTC).toString()
            PriceType.FIAT -> {
                // Use gross price (including VAT) for customer-facing display
                val displayPrice = getGrossPrice()
                val minorUnits = (displayPrice * 100).roundToLong()
                val currency = Amount.Currency.fromCode(priceCurrency)
                Amount(minorUnits, currency).toString()
            }
        }
    }

    /**
     * Get formatted net price (excluding VAT).
     */
    fun getFormattedNetPrice(): String {
        if (priceType != PriceType.FIAT) return ""
        val minorUnits = (price * 100).roundToLong()
        val currency = Amount.Currency.fromCode(priceCurrency)
        return Amount(minorUnits, currency).toString()
    }

    /**
     * Get formatted VAT amount.
     */
    fun getFormattedVatAmount(): String {
        if (priceType != PriceType.FIAT || !vatEnabled) return ""
        val vatAmount = getVatAmount()
        val minorUnits = (vatAmount * 100).roundToLong()
        val currency = Amount.Currency.fromCode(priceCurrency)
        return Amount(minorUnits, currency).toString()
    }

    /**
     * Get formatted gross price (including VAT).
     */
    fun getFormattedGrossPrice(): String {
        if (priceType != PriceType.FIAT) return ""
        val grossPrice = getGrossPrice()
        val minorUnits = (grossPrice * 100).roundToLong()
        val currency = Amount.Currency.fromCode(priceCurrency)
        return Amount(minorUnits, currency).toString()
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
