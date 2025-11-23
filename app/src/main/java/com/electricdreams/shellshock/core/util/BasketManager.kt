package com.electricdreams.shellshock.core.util

import com.electricdreams.shellshock.core.model.BasketItem
import com.electricdreams.shellshock.core.model.Item

/**
 * Manager class for handling the customer's basket.
 */
class BasketManager private constructor() {

    companion object {
        @Volatile
        private var instance: BasketManager? = null

        @JvmStatic
        @Synchronized
        fun getInstance(): BasketManager {
            if (instance == null) {
                instance = BasketManager()
            }
            return instance as BasketManager
        }
    }

    private val basketItems: MutableList<BasketItem> = mutableListOf()

    /**
     * Get all items in the basket.
     * @return List of basket items.
     */
    fun getBasketItems(): List<BasketItem> = ArrayList(basketItems)

    /**
     * Add an item to the basket.
     * @param item Item to add.
     * @param quantity Quantity to add.
     */
    fun addItem(item: Item, quantity: Int) {
        // Check if item is already in basket
        for (basketItem in basketItems) {
            if (basketItem.item.id == item.id) {
                // Increase quantity
                basketItem.quantity += quantity
                return
            }
        }

        // If not in basket, add new entry
        basketItems.add(BasketItem(item, quantity))
    }

    /**
     * Update quantity for an item in the basket.
     * @param itemId ID of the item to update.
     * @param quantity New quantity.
     * @return true if updated successfully, false if not found or quantity is 0.
     */
    fun updateItemQuantity(itemId: String, quantity: Int): Boolean {
        if (quantity <= 0) {
            return removeItem(itemId)
        }

        for (basketItem in basketItems) {
            if (basketItem.item.id == itemId) {
                basketItem.quantity = quantity
                return true
            }
        }

        // If we get here, item is not in basket yet
        return false
    }

    /**
     * Remove an item from the basket.
     * @param itemId ID of the item to remove.
     * @return true if removed successfully, false if not found.
     */
    fun removeItem(itemId: String): Boolean {
        val iterator = basketItems.iterator()
        while (iterator.hasNext()) {
            val basketItem = iterator.next()
            if (basketItem.item.id == itemId) {
                iterator.remove()
                return true
            }
        }
        return false
    }

    /**
     * Clear the basket.
     */
    fun clearBasket() {
        basketItems.clear()
    }

    /**
     * Get the total number of items in the basket.
     */
    fun getTotalItemCount(): Int {
        var count = 0
        for (item in basketItems) {
            count += item.quantity
        }
        return count
    }

    /**
     * Calculate the total price of all items in the basket.
     */
    fun getTotalPrice(): Double {
        var total = 0.0
        for (item in basketItems) {
            total += item.getTotalPrice()
        }
        return total
    }

    /**
     * Calculate the total price in satoshis.
     * @param btcPrice Current BTC price in fiat.
     */
    fun getTotalSatoshis(btcPrice: Double): Long {
        if (btcPrice <= 0) return 0

        val fiatTotal = getTotalPrice()
        val btcAmount = fiatTotal / btcPrice
        return (btcAmount * 100_000_000L).toLong()
    }
}
