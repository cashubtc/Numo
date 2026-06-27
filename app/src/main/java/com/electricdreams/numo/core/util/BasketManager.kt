package com.electricdreams.numo.core.util

import com.electricdreams.numo.core.model.BasketItem
import com.electricdreams.numo.core.model.Item

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

    // ─────────────────────────────────────────────────────────────────────────────
    // Basket history (undo / redo)
    // ─────────────────────────────────────────────────────────────────────────────
    private val undoStack: ArrayDeque<List<BasketItem>> = ArrayDeque()
    private val redoStack: ArrayDeque<List<BasketItem>> = ArrayDeque()
    private val maxHistorySize: Int = 100

    private fun snapshot(): List<BasketItem> = basketItems.map { original ->
        BasketItem(
            item = original.item.copy(),
            quantity = original.quantity,
        )
    }

    private fun restoreFromSnapshot(state: List<BasketItem>) {
        basketItems.clear()
        // Deep copy again so history snapshots remain immutable
        basketItems.addAll(state.map { original ->
            BasketItem(
                item = original.item.copy(),
                quantity = original.quantity,
            )
        })
    }

    /**
     * Capture the current basket state into the undo history.
     * Clears redo history because a new user action invalidates the redo chain.
     */
    private fun captureStateForUndo() {
        // Push current state if there is already at least one state (we don't store initial empty twice)
        undoStack.addLast(snapshot())
        if (undoStack.size > maxHistorySize) {
            undoStack.removeFirst()
        }
        redoStack.clear()
    }

    fun canUndo(): Boolean = undoStack.isNotEmpty()

    fun canRedo(): Boolean = redoStack.isNotEmpty()

    fun undo(): Boolean {
        if (undoStack.isEmpty()) return false
        val current = snapshot()
        val previous = undoStack.removeLast()
        // Current state becomes top of redo stack
        redoStack.addLast(current)
        restoreFromSnapshot(previous)
        return true
    }

    fun redo(): Boolean {
        if (redoStack.isEmpty()) return false
        val current = snapshot()
        val next = redoStack.removeLast()
        // Current state goes back to undo stack
        undoStack.addLast(current)
        restoreFromSnapshot(next)
        return true
    }

    fun clearHistory() {
        undoStack.clear()
        redoStack.clear()
    }

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
        captureStateForUndo()

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
        captureStateForUndo()

        if (quantity <= 0) {
            return removeItemInternal(itemId)
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
        captureStateForUndo()
        return removeItemInternal(itemId)
    }

    private fun removeItemInternal(itemId: String): Boolean {
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
        if (basketItems.isNotEmpty()) {
            captureStateForUndo()
        }
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
     * Calculate the total fiat price of all items in the basket.
     * Only includes items priced in fiat.
     */
    fun getTotalPrice(): Double {
        var total = 0.0
        for (item in basketItems) {
            if (!item.isSatsPrice()) {
                total += item.getTotalPrice()
            }
        }
        return total
    }
    
    /**
     * Calculate the total sats price of all items in the basket.
     * Only includes items priced in sats.
     */
    fun getTotalSatsDirectPrice(): Long {
        var total = 0L
        for (item in basketItems) {
            if (item.isSatsPrice()) {
                total += item.getTotalSats()
            }
        }
        return total
    }

    /**
     * Calculate the total price in satoshis (combining fiat and sats priced items).
     * @param btcPrice Current BTC price in fiat.
     * @param context Android context for preferred unit resolution.
     */
    fun getTotalSatoshis(btcPrice: Double, context: android.content.Context? = null): Long {
        val preferredUnit = if (context != null) {
            MintManager.getInstance(context).getPreferredUnit()
        } else {
            "sat"
        }
        val lowerUnit = preferredUnit.lowercase()
        if (lowerUnit != "sat") {
            val currency = com.electricdreams.numo.core.model.Amount.Currency.fromCode(lowerUnit)
            
            // All items (both originally Sats and Fiat) are now treated as priced in the custom unit
            var total = 0.0
            for (basketItem in basketItems) {
                if (basketItem.isSatsPrice()) {
                    total += basketItem.item.getGrossSats().toDouble() * basketItem.quantity
                } else {
                    total += basketItem.getTotalPrice()
                }
            }
            
            return if (currency.isZeroDecimal()) {
                total.toLong()
            } else {
                Math.round(total * 100)
            }
        }

        // Start with items already priced in sats
        var totalSats = getTotalSatsDirectPrice()
        
        // Convert fiat items to sats if we have a valid BTC price
        val fiatTotal = getTotalPrice()
        if (fiatTotal > 0 && btcPrice > 0) {
            val btcAmount = fiatTotal / btcPrice
            totalSats += (btcAmount * 100_000_000L).toLong()
        }
        
        return totalSats
    }
    
    /**
     * Check if the basket contains items with mixed price types (both fiat and sats).
     */
    fun hasMixedPriceTypes(): Boolean {
        var hasFiat = false
        var hasSats = false
        
        for (item in basketItems) {
            if (item.isSatsPrice()) {
                hasSats = true
            } else {
                hasFiat = true
            }
            
            if (hasFiat && hasSats) return true
        }
        
        return false
    }
}
