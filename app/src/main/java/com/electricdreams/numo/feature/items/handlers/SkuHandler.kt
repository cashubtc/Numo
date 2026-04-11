package com.electricdreams.numo.feature.items.handlers

import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
import com.electricdreams.numo.R
import com.electricdreams.numo.core.util.ItemManager

/**
 * Handles SKU (internal stock keeping unit) input and validation.
 * SKUs are not barcode-scanned; they are entered manually.
 */
class SkuHandler(
    private val skuInput: EditText,
    private val skuContainer: View,
    private val skuErrorText: TextView,
    private val itemManager: ItemManager
) {
    private var editItemId: String? = null
    private var isSkuValid: Boolean = true

    fun initialize() {
        setupSkuValidation()
    }

    fun setEditItemId(itemId: String?) {
        editItemId = itemId
    }

    fun isValid(): Boolean = isSkuValid

    fun getSku(): String = skuInput.text.toString().trim()

    fun setSku(sku: String?) {
        skuInput.setText(sku ?: "")
    }

    fun getSkuInput(): EditText = skuInput

    private fun setupSkuValidation() {
        skuInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                val sku = s.toString().trim()
                validateSku(sku)
            }
        })
    }

    private fun validateSku(sku: String) {
        if (sku.isEmpty()) {
            setSkuError(false)
            return
        }

        val isDuplicate = itemManager.isSkuDuplicate(sku, editItemId)
        setSkuError(isDuplicate)
    }

    private fun setSkuError(hasError: Boolean) {
        isSkuValid = !hasError

        if (hasError) {
            skuContainer.setBackgroundResource(R.drawable.bg_outlined_input_error)
            skuErrorText.visibility = View.VISIBLE
        } else {
            skuContainer.setBackgroundResource(0)
            skuErrorText.visibility = View.GONE
        }
    }
}
