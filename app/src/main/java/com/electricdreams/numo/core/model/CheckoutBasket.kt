package com.electricdreams.numo.core.model

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.util.Date

/**
 * Complete snapshot of a checkout basket at the time of payment.
 * Contains all items with quantities, prices, and VAT information.
 * 
 * This is serialized to JSON and stored with the payment history entry
 * to enable reconstruction of a detailed receipt view.
 */
data class CheckoutBasket(
    /** Unique identifier for this basket */
    @SerializedName("id")
    val id: String = java.util.UUID.randomUUID().toString(),

    /** Timestamp when the checkout was created */
    @SerializedName("checkoutTimestamp")
    val checkoutTimestamp: Long = System.currentTimeMillis(),

    /** All items in the basket */
    @SerializedName("items")
    val items: List<CheckoutBasketItem>,

    /** Currency used for fiat items (primary currency at checkout) */
    @SerializedName("currency")
    val currency: String,

    /** Bitcoin price at time of checkout (if available) */
    @SerializedName("bitcoinPrice")
    val bitcoinPrice: Double? = null,

    /** Total amount in satoshis (final payment amount) */
    @SerializedName("totalSatoshis")
    val totalSatoshis: Long,
) {

    /**
     * Get all fiat-priced items.
     */
    fun getFiatItems(): List<CheckoutBasketItem> = items.filter { it.isFiatPrice() }

    /**
     * Get all sats-priced items.
     */
    fun getSatsItems(): List<CheckoutBasketItem> = items.filter { it.isSatsPrice() }

    /**
     * Check if basket contains mixed price types.
     */
    fun hasMixedPriceTypes(): Boolean {
        val hasFiat = items.any { it.isFiatPrice() }
        val hasSats = items.any { it.isSatsPrice() }
        return hasFiat && hasSats
    }

    /**
     * Check if any items have VAT enabled.
     */
    fun hasVat(): Boolean = items.any { it.vatEnabled && it.vatRate > 0 }

    /**
     * Calculate total item count (sum of all quantities).
     */
    fun getTotalItemCount(): Int = items.sumOf { it.quantity }

    /**
     * Calculate total net amount for fiat items (in minor units/cents).
     */
    fun getFiatNetTotalCents(): Long = getFiatItems().sumOf { it.getNetTotalCents() }

    /**
     * Calculate total VAT amount for fiat items (in minor units/cents).
     */
    fun getFiatVatTotalCents(): Long = getFiatItems().sumOf { it.getTotalVatCents() }

    /**
     * Calculate total gross amount for fiat items (in minor units/cents).
     */
    fun getFiatGrossTotalCents(): Long = getFiatItems().sumOf { it.getGrossTotalCents() }

    /**
     * Calculate total sats for directly sats-priced items.
     */
    fun getSatsDirectTotal(): Long = getSatsItems().sumOf { it.getNetTotalSats() }

    /**
     * Get grouped VAT breakdown by rate.
     * Returns a map of VAT rate (e.g., 20) to total VAT amount in cents.
     */
    fun getVatBreakdown(): Map<Int, Long> {
        return getFiatItems()
            .filter { it.vatEnabled && it.vatRate > 0 }
            .groupBy { it.vatRate }
            .mapValues { (_, items) -> items.sumOf { it.getTotalVatCents() } }
    }

    /**
     * Get the checkout date as a Date object.
     */
    fun getCheckoutDate(): Date = Date(checkoutTimestamp)

    /**
     * Serialize this basket to JSON string for storage.
     */
    fun toJson(): String = Gson().toJson(this)

    companion object {
        /**
         * Deserialize a basket from JSON string.
         * Returns null if parsing fails or json is null/empty.
         */
        fun fromJson(json: String?): CheckoutBasket? {
            if (json.isNullOrEmpty()) return null
            return try {
                Gson().fromJson(json, CheckoutBasket::class.java)
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Create a CheckoutBasket from the current BasketManager state.
         */
        fun fromBasketManager(
            basketManager: com.electricdreams.numo.core.util.BasketManager,
            currency: String,
            bitcoinPrice: Double?,
            totalSatoshis: Long,
        ): CheckoutBasket {
            val items = basketManager.getBasketItems().map { basketItem ->
                CheckoutBasketItem.fromBasketItem(
                    basketItem = basketItem,
                    currencyCode = currency,
                )
            }

            return CheckoutBasket(
                items = items,
                currency = currency,
                bitcoinPrice = bitcoinPrice,
                totalSatoshis = totalSatoshis,
            )
        }
    }
}
