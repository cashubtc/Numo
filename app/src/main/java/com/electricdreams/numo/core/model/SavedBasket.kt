package com.electricdreams.numo.core.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.util.UUID

/**
 * Status of a saved basket.
 */
enum class BasketStatus {
    /** Basket is active and can be edited */
    ACTIVE,
    /** Basket has been paid and is archived */
    PAID
}

/**
 * Model class for a saved basket (tab/table).
 * 
 * Allows merchants to save baskets for later checkout, useful for:
 * - Restaurant tables
 * - Customer tabs
 * - Split payments
 * - Layaway
 */
@Parcelize
data class SavedBasket(
    val id: String = UUID.randomUUID().toString(),
    var name: String? = null,
    var items: List<BasketItem> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    var status: BasketStatus = BasketStatus.ACTIVE,
    var paymentId: String? = null,
    var paidAt: Long? = null
) : Parcelable {
    
    /**
     * Get display name - either custom name or "Basket #X".
     */
    fun getDisplayName(index: Int): String {
        return name?.takeIf { it.isNotBlank() } ?: "Basket #${index + 1}"
    }
    
    /**
     * Get total item count.
     */
    fun getTotalItemCount(): Int {
        return items.sumOf { it.quantity }
    }
    
    /**
     * Get total fiat price (excluding sats items).
     */
    fun getTotalFiatPrice(): Double {
        return items.filter { !it.isSatsPrice() }.sumOf { it.getTotalPrice() }
    }
    
    /**
     * Get total sats price (only sats items).
     */
    fun getTotalSatsPrice(): Long {
        return items.filter { it.isSatsPrice() }.sumOf { it.getTotalSats() }
    }
    
    /**
     * Check if basket has mixed price types (fiat and sats).
     */
    fun hasMixedPriceTypes(): Boolean {
        val hasFiat = items.any { !it.isSatsPrice() }
        val hasSats = items.any { it.isSatsPrice() }
        return hasFiat && hasSats
    }
    
    /**
     * Check if basket is paid/archived.
     */
    fun isPaid(): Boolean = status == BasketStatus.PAID
    
    /**
     * Check if basket is active and editable.
     */
    fun isActive(): Boolean = status == BasketStatus.ACTIVE
    
    /**
     * Get a short summary of items (e.g., "4 × Coffee, 2 × Pizza + 2 more").
     * @param maxItems Maximum number of item types to show before "+ X more"
     */
    fun getItemsSummary(maxItems: Int = 2): String {
        if (items.isEmpty()) return ""
        
        // Group by item name and sum quantities
        val grouped = items
            .groupBy { it.item.name ?: "Item" }
            .map { (name, items) -> Pair(name, items.sumOf { it.quantity }) }
            .sortedByDescending { it.second }
        
        val shown = grouped.take(maxItems)
        val remaining = grouped.size - maxItems
        
        val summaryParts = shown.map { (name, qty) -> "$qty × $name" }
        
        return if (remaining > 0) {
            "${summaryParts.joinToString(", ")} + $remaining more"
        } else {
            summaryParts.joinToString(", ")
        }
    }
}
