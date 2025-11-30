package com.electricdreams.numo.core.model

import com.google.gson.annotations.SerializedName
import com.electricdreams.numo.core.util.CurrencyManager

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
    fun getNetTotalCents(): Long = netPriceCents * quantity

    /**
     * Calculate the net total in sats (for sats-priced items).
     */
    fun getNetTotalSats(): Long = priceSats * quantity

    /**
     * Calculate VAT amount per unit in minor units.
     */
    fun getVatPerUnitCents(): Long {
        if (!vatEnabled || vatRate <= 0 || priceType != "FIAT") return 0L
        return (netPriceCents * vatRate) / 100
    }

    /**
     * Calculate total VAT amount for this line item in minor units.
     */
    fun getTotalVatCents(): Long = getVatPerUnitCents() * quantity

    /**
     * Calculate gross price per unit (including VAT) in minor units.
     */
    fun getGrossPricePerUnitCents(): Long = netPriceCents + getVatPerUnitCents()

    /**
     * Calculate gross total (including VAT) in minor units.
     */
    fun getGrossTotalCents(): Long = getGrossPricePerUnitCents() * quantity

    /**
     * Check if this item is priced in sats.
     */
    fun isSatsPrice(): Boolean = priceType == "SATS"

    /**
     * Check if this item is priced in fiat.
     */
    fun isFiatPrice(): Boolean = priceType == "FIAT"

    companion object {
        /**
         * Create a CheckoutBasketItem from a BasketItem snapshot.
         */
        fun fromBasketItem(
            basketItem: BasketItem,
            currencyCode: String
        ): CheckoutBasketItem {
            val item = basketItem.item
            return CheckoutBasketItem(
                itemId = item.id ?: "",
                uuid = item.uuid,
                name = item.name ?: "Unknown Item",
                variationName = item.variationName,
                sku = item.sku,
                category = item.category,
                quantity = basketItem.quantity,
                priceType = item.priceType.name,
                netPriceCents = (item.price * 100).toLong(),
                priceSats = item.priceSats,
                priceCurrency = currencyCode,
                vatEnabled = item.vatEnabled,
                vatRate = item.vatRate,
            )
        }
    }
}
