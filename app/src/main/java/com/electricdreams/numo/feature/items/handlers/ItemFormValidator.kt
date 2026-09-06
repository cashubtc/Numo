package com.electricdreams.numo.feature.items.handlers

import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.electricdreams.numo.R
import com.electricdreams.numo.core.model.PriceType

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
        val gtin: String = "",
        val priceUnit: String? = null,
        val priceAtomic: Long? = null,
        val grossPriceAtomic: Long? = null,
        val priceIssuerScope: String? = null,
    )

    /**
     * Validates all form fields and returns the result.
     */
    fun validate(): ValidationResult {
        // Validate name
        val name = nameInput.text.toString().trim()
        if (name.isEmpty()) {
            nameInput.error = activity.getString(R.string.item_entry_error_name_required)
            nameInput.requestFocus()
            return ValidationResult(false)
        }

        // Validate SKU is not duplicate
        if (!skuHandler.isValid()) {
            Toast.makeText(activity, R.string.item_list_toast_sku_not_unique, Toast.LENGTH_SHORT).show()
            skuHandler.getSkuInput().requestFocus()
            return ValidationResult(false)
        }

        // Validate GTIN is not duplicate
        if (!gtinHandler.isValid()) {
            Toast.makeText(activity, R.string.item_list_toast_gtin_not_unique, Toast.LENGTH_SHORT).show()
            gtinHandler.getGtinInput().requestFocus()
            return ValidationResult(false)
        }

        val isSats = pricingHandler.getCurrentPriceType() == PriceType.SATS
        val priceInput = if (isSats) pricingHandler.getSatsInput() else pricingHandler.getPriceInput()
        val price = priceInput.text.toString().trim()
        val inputError = when {
            price.isEmpty() -> if (isSats) {
                R.string.item_entry_error_sats_required
            } else {
                R.string.item_entry_error_price_required
            }
            price.startsWith("-") -> if (isSats) {
                R.string.item_entry_error_sats_positive
            } else {
                R.string.item_entry_error_price_positive
            }
            else -> null
        }
        if (inputError != null) {
            priceInput.error = activity.getString(inputError)
            priceInput.requestFocus()
            return ValidationResult(false)
        }
        if (!pricingHandler.isValidFiatPrice(price)) {
            pricingHandler.showPricePrecisionError()
            priceInput.requestFocus()
            return ValidationResult(false)
        }

        val enteredAtomic = try {
            pricingHandler.getEnteredAtomicAmount()
        } catch (e: ArithmeticException) {
            priceInput.error = activity.getString(R.string.item_entry_error_price_too_large)
            priceInput.requestFocus()
            return ValidationResult(false)
        } catch (e: IllegalArgumentException) {
            pricingHandler.showPricePrecisionError()
            priceInput.requestFocus()
            return ValidationResult(false)
        }
        val vatAmounts = try {
            VatCalculator.calculateAtomicAmounts(
                enteredAmount = enteredAtomic,
                vatRate = if (pricingHandler.isVatEnabled()) pricingHandler.getVatRate() else 0,
                priceIncludesVat = pricingHandler.isPriceIncludesVat(),
            )
        } catch (e: ArithmeticException) {
            priceInput.error = activity.getString(R.string.item_entry_error_price_too_large)
            priceInput.requestFocus()
            return ValidationResult(false)
        }
        val satsPrice = if (isSats) vatAmounts.net.value else 0L
        val fiatPrice = if (isSats) 0.0 else {
            vatAmounts.net.toMajorUnits(pricingHandler.getSelectedUnitDescriptor()).toDouble()
        }

        // Validate inventory if tracking enabled
        var quantity = 0
        var alertThreshold = 5

        if (inventoryHandler.isTrackingEnabled()) {
            quantity = inventoryHandler.getQuantity()
            if (quantity < 0) {
                inventoryHandler.getQuantityInput().error = activity.getString(R.string.item_entry_error_quantity_positive)
                inventoryHandler.getQuantityInput().requestFocus()
                return ValidationResult(false)
            }

            if (inventoryHandler.isAlertEnabled()) {
                alertThreshold = inventoryHandler.getAlertThreshold()
                if (alertThreshold < 0) {
                    inventoryHandler.getAlertThresholdInput().error = activity.getString(R.string.item_entry_error_threshold_positive)
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
            gtin = gtinHandler.getGtin(),
            priceUnit = vatAmounts.net.unit.value,
            priceAtomic = vatAmounts.net.value,
            grossPriceAtomic = vatAmounts.gross.value,
            priceIssuerScope = vatAmounts.net.asset.issuerScope,
        )
    }
}
