package com.electricdreams.numo.feature.items.handlers

import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.electricdreams.numo.R
import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.core.model.PriceType
import com.electricdreams.numo.core.util.CurrencyManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * Handles all pricing-related UI and logic including:
 * - Price type toggle (Fiat vs Bitcoin)
 * - VAT section management
 * - Price breakdown calculations
 * - Input validation for prices
 */
class PricingHandler(
    private val priceTypeToggle: MaterialButtonToggleGroup,
    private val btnPriceFiat: MaterialButton,
    private val btnPriceBitcoin: MaterialButton,
    private val fiatPriceContainer: LinearLayout,
    private val satsPriceContainer: LinearLayout,
    private val priceInput: EditText,
    private val satsInput: EditText,
    private val currencySymbol: TextView,
    private val currencyCode: TextView,
    private val vatSectionCard: View,
    private val switchVatEnabled: SwitchMaterial,
    private val vatFieldsContainer: LinearLayout,
    private val switchPriceIncludesVat: SwitchMaterial,
    private val vatRateInput: EditText,
    private val priceBreakdownContainer: LinearLayout,
    private val textNetPrice: TextView,
    private val textVatLabel: TextView,
    private val textVatAmount: TextView,
    private val textGrossPrice: TextView,
    private val currencyManager: CurrencyManager
) {
    private var currentPriceType: PriceType = PriceType.FIAT

    /**
     * Initializes pricing controls with listeners and validation.
     */
    fun initialize() {
        setupPriceTypeToggle()
        setupVatSection()
        setupInputValidation()
        updateCurrencyDisplay()
        updateVatSectionVisibility()
    }

    /**
     * Gets the current price type selection.
     */
    fun getCurrentPriceType(): PriceType = currentPriceType

    /**
     * Sets the current price type (used when loading existing item data).
     */
    fun setCurrentPriceType(priceType: PriceType) {
        currentPriceType = priceType
        when (priceType) {
            PriceType.FIAT -> {
                priceTypeToggle.check(R.id.btn_price_fiat)
                fiatPriceContainer.visibility = View.VISIBLE
                satsPriceContainer.visibility = View.GONE
            }
            PriceType.SATS -> {
                priceTypeToggle.check(R.id.btn_price_bitcoin)
                fiatPriceContainer.visibility = View.GONE
                satsPriceContainer.visibility = View.VISIBLE
            }
        }
        updateVatSectionVisibility()
    }

    /**
     * Gets the VAT rate from the input field.
     */
    fun getVatRate(): Int = vatRateInput.text.toString().toIntOrNull() ?: 0

    /**
     * Gets whether VAT is enabled.
     */
    fun isVatEnabled(): Boolean = switchVatEnabled.isChecked

    /**
     * Gets whether the entered price includes VAT.
     */
    fun isPriceIncludesVat(): Boolean = switchPriceIncludesVat.isChecked

    /**
     * Gets the entered fiat price as a Double.
     * Normalizes comma decimal separator to period.
     */
    fun getEnteredFiatPrice(): Double {
        val priceStr = priceInput.text.toString().trim().replace(",", ".")
        return priceStr.toDoubleOrNull() ?: 0.0
    }

    /**
     * Gets the entered sats price as a Long.
     */
    fun getEnteredSatsPrice(): Long {
        val satsStr = satsInput.text.toString().trim()
        return satsStr.toLongOrNull() ?: 0L
    }

    /**
     * Sets VAT-related fields (used when loading existing item data).
     */
    fun setVatFields(vatEnabled: Boolean, vatRate: Int, priceIncludesVat: Boolean) {
        switchVatEnabled.isChecked = vatEnabled
        vatFieldsContainer.visibility = if (vatEnabled) View.VISIBLE else View.GONE
        priceBreakdownContainer.visibility = if (vatEnabled) View.VISIBLE else View.GONE
        vatRateInput.setText(vatRate.toString())
        switchPriceIncludesVat.isChecked = priceIncludesVat
        updateVatSectionVisibility()
        updatePriceBreakdown()
    }

    /**
     * Sets the fiat price input value (used when loading existing item data).
     */
    fun setFiatPrice(price: Double) {
        priceInput.setText(formatFiatPrice(price))
    }

    /**
     * Sets the sats price input value (used when loading existing item data).
     */
    fun setSatsPrice(sats: Long) {
        satsInput.setText(sats.toString())
    }

    /**
     * Updates the currency display based on current settings.
     */
    fun updateCurrencyDisplay() {
        currencySymbol.text = currencyManager.getCurrentSymbol()
        currencyCode.text = currencyManager.getCurrentCurrency()
    }

    /**
     * Validates the fiat price format (max 2 decimal places, accepts . or , as separator).
     */
    fun isValidFiatPrice(price: String): Boolean {
        val pattern = "^(?:\\d+(?:[.,]\\d{0,2})?|[.,]\\d{1,2})$".toRegex()
        return pattern.matches(price)
    }

    /**
     * Gets the fiat price input EditText for error handling.
     */
    fun getPriceInput(): EditText = priceInput

    /**
     * Gets the sats price input EditText for error handling.
     */
    fun getSatsInput(): EditText = satsInput

    private fun setupPriceTypeToggle() {
        // Set initial selection
        priceTypeToggle.check(R.id.btn_price_fiat)

        priceTypeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    R.id.btn_price_fiat -> {
                        currentPriceType = PriceType.FIAT
                        fiatPriceContainer.visibility = View.VISIBLE
                        satsPriceContainer.visibility = View.GONE
                        updateVatSectionVisibility()
                    }
                    R.id.btn_price_bitcoin -> {
                        currentPriceType = PriceType.SATS
                        fiatPriceContainer.visibility = View.GONE
                        satsPriceContainer.visibility = View.VISIBLE
                        updateVatSectionVisibility()
                    }
                }
            }
        }
    }

    private fun setupVatSection() {
        // VAT rate input - integers only (0-99)
        vatRateInput.filters = arrayOf(InputFilter.LengthFilter(2))
        vatRateInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updatePriceBreakdown()
            }
        })

        // VAT enabled toggle
        switchVatEnabled.setOnCheckedChangeListener { _, isChecked ->
            vatFieldsContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
            priceBreakdownContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
            updatePriceBreakdown()
        }

        // Price includes VAT toggle
        switchPriceIncludesVat.setOnCheckedChangeListener { _, _ ->
            updatePriceBreakdown()
        }
    }

    private fun updateVatSectionVisibility() {
        // VAT section is always visible for both fiat and Bitcoin prices
        vatSectionCard.visibility = View.VISIBLE

        // Show breakdown when VAT is enabled
        priceBreakdownContainer.visibility = if (switchVatEnabled.isChecked) View.VISIBLE else View.GONE
    }

    private fun updatePriceBreakdown() {
        if (!switchVatEnabled.isChecked) {
            priceBreakdownContainer.visibility = View.GONE
            return
        }

        priceBreakdownContainer.visibility = View.VISIBLE

        val vatRate = getVatRate()
        val breakdown = when (currentPriceType) {
            PriceType.FIAT -> {
                VatCalculator.calculateFiatBreakdown(
                    enteredPrice = getEnteredFiatPrice(),
                    vatRate = vatRate,
                    priceIncludesVat = switchPriceIncludesVat.isChecked,
                    currency = currencyManager.getCurrentCurrency()
                )
            }
            PriceType.SATS -> {
                VatCalculator.calculateSatsBreakdown(
                    enteredSats = getEnteredSatsPrice(),
                    vatRate = vatRate,
                    priceIncludesVat = switchPriceIncludesVat.isChecked
                )
            }
        }

        textNetPrice.text = breakdown.netPrice
        textVatLabel.text = breakdown.vatLabel
        textVatAmount.text = breakdown.vatAmount
        textGrossPrice.text = breakdown.grossPrice
    }

    private fun setupInputValidation() {
        // Fiat price input - allow both . and , as decimal separators, max 2 decimal places
        priceInput.addTextChangedListener(object : TextWatcher {
            private var current = ""

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                if (s.toString() != current) {
                    priceInput.removeTextChangedListener(this)

                    val cleanString = s.toString()

                    // Find decimal separator (either . or ,)
                    val decimalSeparator = if (cleanString.contains(",")) "," else "."

                    // Validate decimal places
                    if (cleanString.contains(decimalSeparator)) {
                        val parts = cleanString.split(decimalSeparator)
                        if (parts.size > 1 && parts[1].length > 2) {
                            // Truncate to 2 decimal places
                            val truncated = "${parts[0]}$decimalSeparator${parts[1].substring(0, 2)}"
                            priceInput.setText(truncated)
                            priceInput.setSelection(truncated.length)
                        }
                    }

                    current = priceInput.text.toString()
                    priceInput.addTextChangedListener(this)

                    // Update VAT breakdown in real-time
                    updatePriceBreakdown()
                }
            }
        })

        // Sats input - integers only (already set in XML with inputType="number")
        satsInput.filters = arrayOf(InputFilter { source, start, end, dest, dstart, dend ->
            // Only allow digits
            for (i in start until end) {
                if (!Character.isDigit(source[i])) {
                    return@InputFilter ""
                }
            }
            null
        })

        // Add text watcher for sats input to update VAT breakdown
        satsInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updatePriceBreakdown()
            }
        })
    }

    private fun formatFiatPrice(price: Double): String {
        val currency = Amount.Currency.fromCode(currencyManager.getCurrentCurrency())
        val minorUnits = Math.round(price * 100)
        val amount = Amount(minorUnits, currency)
        return amount.toStringWithoutSymbol()
    }
}
