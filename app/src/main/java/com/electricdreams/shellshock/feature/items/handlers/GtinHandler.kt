package com.electricdreams.shellshock.feature.items.handlers

import android.content.Intent
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AppCompatActivity
import com.electricdreams.shellshock.R
import com.electricdreams.shellshock.core.util.ItemManager
import com.electricdreams.shellshock.feature.items.BarcodeScannerActivity

/**
 * Handles GTIN input, validation, and barcode scanning functionality.
 */
class GtinHandler(
    private val activity: AppCompatActivity,
    private val gtinInput: EditText,
    private val gtinContainer: View,
    private val gtinErrorText: TextView,
    private val scanBarcodeButton: ImageButton,
    private val itemManager: ItemManager,
    private val barcodeScanLauncher: ActivityResultLauncher<Intent>
) {
    private var editItemId: String? = null
    private var isGtinValid: Boolean = true

    /**
     * Initializes GTIN input validation and barcode button.
     */
    fun initialize() {
        setupGtinValidation()
        scanBarcodeButton.setOnClickListener { launchBarcodeScanner() }
    }

    /**
     * Sets the item ID for edit mode (used for duplicate validation).
     */
    fun setEditItemId(itemId: String?) {
        editItemId = itemId
    }

    /**
     * Returns whether the current GTIN is valid.
     */
    fun isValid(): Boolean = isGtinValid

    /**
     * Gets the current GTIN value.
     */
    fun getGtin(): String = gtinInput.text.toString().trim()

    /**
     * Sets the GTIN value (used when loading existing item data).
     */
    fun setGtin(gtin: String?) {
        gtinInput.setText(gtin ?: "")
    }

    /**
     * Gets the GTIN EditText for focus handling.
     */
    fun getGtinInput(): EditText = gtinInput

    /**
     * Handles barcode scan result.
     */
    fun handleBarcodeScanResult(barcodeValue: String?) {
        if (!barcodeValue.isNullOrEmpty()) {
            if (itemManager.isGtinDuplicate(barcodeValue, editItemId)) {
                Toast.makeText(activity, "This GTIN is already used by another item", Toast.LENGTH_LONG).show()
            } else {
                gtinInput.setText(barcodeValue)
                Toast.makeText(activity, "GTIN scanned successfully", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupGtinValidation() {
        gtinInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                val gtin = s.toString().trim()
                validateGtin(gtin)
            }
        })
    }

    private fun validateGtin(gtin: String) {
        if (gtin.isEmpty()) {
            // Empty GTIN is valid (optional field)
            setGtinError(false)
            return
        }

        val isDuplicate = itemManager.isGtinDuplicate(gtin, editItemId)
        setGtinError(isDuplicate)
    }

    private fun setGtinError(hasError: Boolean) {
        isGtinValid = !hasError

        if (hasError) {
            gtinContainer.setBackgroundResource(R.drawable.bg_input_field_error)
            gtinErrorText.visibility = View.VISIBLE
        } else {
            gtinContainer.setBackgroundResource(0) // Remove background (handled by card)
            gtinErrorText.visibility = View.GONE
        }
    }

    private fun launchBarcodeScanner() {
        val intent = Intent(activity, BarcodeScannerActivity::class.java)
        barcodeScanLauncher.launch(intent)
    }
}
