package com.electricdreams.shellshock.core.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Model class for an item in the merchant's catalog.
 *
 * Kotlin version of the original Java Item class.
 */
@Parcelize
data class Item(
    var id: String? = null, // Unique identifier for the item
    var name: String? = null, // Item name
    var variationName: String? = null, // Optional variation name
    var sku: String? = null, // Stock keeping unit
    var description: String? = null, // Item description
    var category: String? = null, // Category
    var gtin: String? = null, // Global Trade Item Number
    var price: Double = 0.0, // Price in default currency
    var quantity: Int = 0, // Available quantity
    var alertEnabled: Boolean = false, // Whether stock alerts are enabled
    var alertThreshold: Int = 0, // Threshold for stock alerts
    var imagePath: String? = null, // Path to item image (can be null)
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

    // Java interop helper for isAlertEnabled() to match original Java API
    fun isAlertEnabled(): Boolean = alertEnabled
}
