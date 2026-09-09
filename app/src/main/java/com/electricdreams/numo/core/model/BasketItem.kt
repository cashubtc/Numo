package com.electricdreams.numo.core.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Model class for an item in the customer's basket with quantity.
 */
@Parcelize
data class BasketItem(
    var item: Item,
    var quantity: Int,
) : Parcelable {

    /**
     * Increase quantity by one.
     */
    fun increaseQuantity() {
        quantity++
    }

    /**
     * Decrease quantity by one, ensuring it doesn't go below 0.
     * @return true if quantity is greater than 0 after decrease, false otherwise.
     */
    fun decreaseQuantity(): Boolean {
        if (quantity > 0) {
            quantity--
            return quantity > 0
        }
        return false
    }

    /**
     * Calculate the total fiat price for this basket item (gross price * quantity).
     * Uses gross price (including VAT) for customer-facing calculations.
     * Only valid for items with FIAT price type.
     * @return Total fiat price including VAT.
     */
    fun getTotalPrice(): Double = item.getGrossPrice() * quantity
    
    /**
     * Calculate the total sats price for this basket item (priceSats * quantity).
     * Only valid for items with SATS price type.
     * @return Total sats.
     */
    fun getTotalSats(): Long = item.priceSats * quantity

    /** Gross line total with its unit and issuer identity preserved. */
    fun getGrossAtomicAmount(legacyFiatUnit: String): AtomicAmount {
        require(quantity >= 0) { "Basket quantity cannot be negative" }
        return item.getGrossAtomicAmount(legacyFiatUnit) * quantity
    }
    
    /**
     * Check if this item is priced in sats.
     */
    fun isSatsPrice(): Boolean = item.priceType == PriceType.SATS
}
