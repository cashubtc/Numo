package com.electricdreams.shellshock.feature.items.handlers

import com.electricdreams.shellshock.core.model.Item
import com.electricdreams.shellshock.core.model.PriceType
import java.util.UUID

/**
 * Builder class for creating or updating Item objects from form data.
 * Handles the logic of populating Item fields based on edit/create mode.
 */
class ItemBuilder {

    /**
     * Builds an Item from the validated form data.
     * 
     * @param validationResult The validated form data
     * @param isEditMode Whether we're editing an existing item
     * @param editItemId The ID of the item being edited (if in edit mode)
     * @param currentItem The current item being edited (if in edit mode)
     * @param variationName The variation name from the form
     * @param category The selected category
     * @param description The description from the form
     * @param sku The SKU value
     * @param priceType The selected price type (FIAT or SATS)
     * @param currency The current currency code
     * @param vatEnabled Whether VAT is enabled
     * @param vatRate The VAT rate (if VAT is enabled)
     * @param trackInventory Whether inventory tracking is enabled
     * @param alertEnabled Whether low stock alerts are enabled
     * @param hasNewImage Whether a new image has been selected
     * @return The constructed Item object
     */
    fun build(
        validationResult: ItemFormValidator.ValidationResult,
        isEditMode: Boolean,
        editItemId: String?,
        currentItem: Item?,
        variationName: String,
        category: String?,
        description: String,
        sku: String,
        gtin: String,
        priceType: PriceType,
        currency: String,
        vatEnabled: Boolean,
        vatRate: Int,
        trackInventory: Boolean,
        alertEnabled: Boolean,
        hasNewImage: Boolean
    ): Item {
        return Item().apply {
            if (isEditMode) {
                id = editItemId
                // Preserve existing UUID
                uuid = currentItem?.uuid ?: UUID.randomUUID().toString()
                if (currentItem?.imagePath != null && !hasNewImage) {
                    imagePath = currentItem.imagePath
                }
            } else {
                id = UUID.randomUUID().toString()
                uuid = UUID.randomUUID().toString()
            }

            name = validationResult.name
            this.variationName = variationName
            this.category = category
            this.description = description
            this.sku = sku
            this.gtin = gtin

            // Pricing
            this.priceType = priceType
            priceCurrency = currency
            when (priceType) {
                PriceType.FIAT -> {
                    price = validationResult.fiatPrice // Always store net price (excluding VAT)
                    priceSats = 0L
                }
                PriceType.SATS -> {
                    price = 0.0
                    priceSats = validationResult.satsPrice
            }
            }

            // VAT (available for both fiat and Bitcoin items)
            this.vatEnabled = vatEnabled
            this.vatRate = if (vatEnabled) vatRate else 0

            // Inventory
            this.trackInventory = trackInventory
            quantity = if (trackInventory) validationResult.quantity else 0
            this.alertEnabled = alertEnabled
            alertThreshold = if (alertEnabled) validationResult.alertThreshold else 5
        }
    }
}
