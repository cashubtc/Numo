package com.electricdreams.shellshock.core.model

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
     * Calculate the total price for this basket item (price * quantity).
     * @return Total price.
     */
    fun getTotalPrice(): Double = item.price * quantity
}
