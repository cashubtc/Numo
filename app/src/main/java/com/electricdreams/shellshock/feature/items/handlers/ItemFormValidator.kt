package com.electricdreams.shellshock.feature.items.handlers

import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.electricdreams.shellshock.core.model.PriceType

/**
 * Handles validation for all item form fields.
 * Centralizes validation logic and provides consistent error handling.
 */
class ItemFormValidator(
    private val activity: AppCompatActivity,
    private val nameInput: EditText,
    private val pricingHandler: PricingHandler,
    private val inventoryHandler: InventoryHandler,
    private val skuHandler: SkuHandler,
    private val gtinHandler: GtinHandler
) {
    /**
     * Validation result containing success state and validated values.
     */
    data class ValidationResult(
        val isValid: Boolean,
        val name: String = "",
        val fiatPrice: Double = 0.0,
        val satsPrice: Long = 0L,
        val quantity: Int = 0,
        val alertThreshold: Int = 5,
        val sku: String = "",
        val gtin: String = ""
    )

    /**
     * Validates all form fields and returns the result.
     */
    fun validate(): ValidationResult {
        // Validate name
        val name = nameInput.text.toString().trim()
        if (name.isEmpty()) {
            nameInput.error = "Item name is required"
            nameInput.requestFocus()
            return ValidationResult(false)
        }

        // Validate SKU is not duplicate
        if (!skuHandler.isValid()) {
            Toast.makeText(activity, "Please use a unique SKU", Toast.LENGTH_SHORT).show()
            skuHandler.getSkuInput().requestFocus()
            return ValidationResult(false)
        }

        // Validate GTIN is not duplicate
        if (!gtinHandler.isValid()) {
            Toast.makeText(activity, "Please use a unique GTIN", Toast.LENGTH_SHORT).show()
            gtinHandler.getGtinInput().requestFocus()
            return ValidationResult(false)
        }

        // Validate and get price based on type
        var fiatPrice = 0.0
        var satsPrice = 0L

        when (pricingHandler.getCurrentPriceType()) {
            PriceType.FIAT -> {
                val priceInput = pricingHandler.getPriceInput()
                val priceStr = priceInput.text.toString().trim()
                if (priceStr.isEmpty()) {
                    priceInput.error = "Price is required"
                    priceInput.requestFocus()
                    return ValidationResult(false)
                }

                // Normalize decimal separator: replace comma with period for parsing
                val normalizedPriceStr = priceStr.replace(",", ".")
                val enteredPrice = normalizedPriceStr.toDoubleOrNull() ?: 0.0
                if (enteredPrice < 0) {
                    priceInput.error = "Price must be positive"
                    priceInput.requestFocus()
                    return ValidationResult(false)
                }

                // Validate max 2 decimal places (accepting both . and , as separators)
                if (!pricingHandler.isValidFiatPrice(priceStr)) {
                    priceInput.error = "Maximum 2 decimal places allowed"
                    priceInput.requestFocus()
                    return ValidationResult(false)
                }

                // Convert entered price to net price if VAT is enabled
                fiatPrice = VatCalculator.calculateNetPriceForStorage(
                    enteredPrice = enteredPrice,
                    vatEnabled = pricingHandler.isVatEnabled(),
                    priceIncludesVat = pricingHandler.isPriceIncludesVat(),
                    vatRate = pricingHandler.getVatRate()
                )
            }
            PriceType.SATS -> {
                val satsInput = pricingHandler.getSatsInput()
                val satsStr = satsInput.text.toString().trim()
                if (satsStr.isEmpty()) {
                    satsInput.error = "Price in sats is required"
                    satsInput.requestFocus()
                    return ValidationResult(false)
                }

                satsPrice = satsStr.toLongOrNull() ?: 0L
                if (satsPrice < 0) {
                    satsInput.error = "Sats must be positive"
                    satsInput.requestFocus()
                    return ValidationResult(false)
                }

                // Validate it's an integer (no decimals)
                if (satsStr.contains(".") || satsStr.contains(",")) {
                    satsInput.error = "Sats must be a whole number"
                    satsInput.requestFocus()
                    return ValidationResult(false)
                }
            }
        }

        // Validate inventory if tracking enabled
        var quantity = 0
        var alertThreshold = 5

        if (inventoryHandler.isTrackingEnabled()) {
            quantity = inventoryHandler.getQuantity()
            if (quantity < 0) {
                inventoryHandler.getQuantityInput().error = "Quantity must be positive"
                inventoryHandler.getQuantityInput().requestFocus()
                return ValidationResult(false)
            }

            if (inventoryHandler.isAlertEnabled()) {
                alertThreshold = inventoryHandler.getAlertThreshold()
                if (alertThreshold < 0) {
                    inventoryHandler.getAlertThresholdInput().error = "Threshold must be positive"
                    inventoryHandler.getAlertThresholdInput().requestFocus()
                    return ValidationResult(false)
                }
            }
        }

        return ValidationResult(
            isValid = true,
            name = name,
            fiatPrice = fiatPrice,
            satsPrice = satsPrice,
            quantity = quantity,
            alertThreshold = alertThreshold,
            sku = skuHandler.getSku(),
            gtin = gtinHandler.getGtin()
        )
    }
}
