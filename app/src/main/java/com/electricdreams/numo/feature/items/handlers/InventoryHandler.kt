package com.electricdreams.numo.feature.items.handlers

import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * Handles inventory tracking UI and logic including:
 * - Track inventory toggle
 * - Quantity input
 * - Low stock alert settings
 */
class InventoryHandler(
    private val switchTrackInventory: MaterialSwitch,
    private val inventoryFieldsContainer: LinearLayout,
    private val quantityInput: EditText,
    private val alertCheckbox: MaterialSwitch,
    private val alertThresholdContainer: LinearLayout,
    private val alertThresholdInput: EditText
) {
    /**
     * Initializes inventory controls with listeners.
     */
    fun initialize() {
        setupListeners()
    }

    /**
     * Gets whether inventory tracking is enabled.
     */
    fun isTrackingEnabled(): Boolean = switchTrackInventory.isChecked

    /**
     * Gets whether low stock alert is enabled.
     */
    fun isAlertEnabled(): Boolean = switchTrackInventory.isChecked && alertCheckbox.isChecked

    /**
     * Gets the entered quantity value.
     */
    fun getQuantity(): Int {
        val quantityStr = quantityInput.text.toString().trim()
        return if (quantityStr.isNotEmpty()) quantityStr.toIntOrNull() ?: 0 else 0
    }

    /**
     * Gets the entered alert threshold value.
     */
    fun getAlertThreshold(): Int {
        val thresholdStr = alertThresholdInput.text.toString().trim()
        return if (thresholdStr.isNotEmpty()) thresholdStr.toIntOrNull() ?: 5 else 5
    }

    /**
     * Gets the quantity EditText for error handling.
     */
    fun getQuantityInput(): EditText = quantityInput

    /**
     * Gets the alert threshold EditText for error handling.
     */
    fun getAlertThresholdInput(): EditText = alertThresholdInput

    /**
     * Sets inventory tracking enabled state (used when loading existing item data).
     */
    fun setTrackingEnabled(enabled: Boolean) {
        switchTrackInventory.isChecked = enabled
        inventoryFieldsContainer.visibility = if (enabled) View.VISIBLE else View.GONE
    }

    /**
     * Sets the quantity value (used when loading existing item data).
     */
    fun setQuantity(quantity: Int) {
        quantityInput.setText(quantity.toString())
    }

    /**
     * Sets alert settings (used when loading existing item data).
     */
    fun setAlertSettings(alertEnabled: Boolean, threshold: Int) {
        alertCheckbox.isChecked = alertEnabled
        alertThresholdContainer.visibility = if (alertEnabled) View.VISIBLE else View.GONE
        alertThresholdInput.setText(threshold.toString())
    }

    /**
     * Validates inventory fields if tracking is enabled.
     * @return Error message if validation fails, null otherwise
     */
    fun validate(): String? {
        if (!switchTrackInventory.isChecked) return null

        val quantity = getQuantity()
        if (quantity < 0) {
            return "quantity_negative"
        }

        if (alertCheckbox.isChecked) {
            val threshold = getAlertThreshold()
            if (threshold < 0) {
                return "threshold_negative"
            }
        }

        return null
    }

    private fun setupListeners() {
        switchTrackInventory.setOnCheckedChangeListener { _, isChecked ->
            inventoryFieldsContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        alertCheckbox.setOnCheckedChangeListener { _, isChecked ->
            alertThresholdContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
    }
}
