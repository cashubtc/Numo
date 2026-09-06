package com.electricdreams.numo.feature.items.handlers

import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.electricdreams.numo.R
import com.electricdreams.numo.core.model.AssetId
import com.electricdreams.numo.core.model.AtomicAmount
import com.electricdreams.numo.core.model.PriceType
import com.electricdreams.numo.core.model.UnitDescriptor
import com.electricdreams.numo.core.model.UnitId
import com.electricdreams.numo.core.model.UnitKind
import com.electricdreams.numo.core.util.CurrencyManager
import com.electricdreams.numo.core.util.MintManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import java.math.BigDecimal

/** Handles item pricing, explicit unit selection, input precision, and VAT display. */
class PricingHandler(
    private val priceTypeToggle: MaterialButtonToggleGroup,
    private val btnPriceFiat: MaterialButton,
    private val btnPriceBitcoin: MaterialButton,
    private val priceUnitLayout: TextInputLayout,
    private val priceUnitInput: MaterialAutoCompleteTextView,
    private val priceUnitWarning: TextView,
    private val fiatPriceLayout: TextInputLayout,
    private val satsPriceLayout: TextInputLayout,
    private val priceInput: EditText,
    private val satsInput: EditText,
    private val vatSectionCard: View,
    private val switchVatEnabled: MaterialSwitch,
    private val vatFieldsContainer: LinearLayout,
    private val switchPriceIncludesVat: MaterialSwitch,
    private val vatRateInput: EditText,
    private val priceBreakdownContainer: LinearLayout,
    private val textNetPrice: TextView,
    private val textVatLabel: TextView,
    private val textVatAmount: TextView,
    private val textGrossPrice: TextView,
    private val currencyManager: CurrencyManager,
) {
    private data class PriceUnitOption(
        val asset: AssetId,
        val label: String,
        val directlyChargeable: Boolean,
        val convertibleToSat: Boolean = false,
    )

    private var currentPriceType: PriceType = PriceType.FIAT
    private var unitAwareMode: Boolean = false
    private var priceUnitOptions: MutableList<PriceUnitOption> = mutableListOf()
    private var selectedPriceAsset: AssetId = AssetId.global(
        UnitId.of(currencyManager.getCurrentCurrency()),
    )

    fun initialize() {
        setupUnitSelection()
        setupPriceTypeToggle()
        setupVatSection()
        setupInputValidation()
        updateCurrencyDisplay()
        updateVatSectionVisibility()
    }

    fun getCurrentPriceType(): PriceType = currentPriceType

    fun getSelectedPriceAsset(): AssetId = selectedPriceAsset

    fun getSelectedUnitDescriptor(): UnitDescriptor =
        UnitDescriptor.defaultFor(selectedPriceAsset.unit)

    fun setCurrentPriceType(priceType: PriceType) {
        val targetUnit = when (priceType) {
            PriceType.FIAT -> UnitId.of(currencyManager.getCurrentCurrency())
            PriceType.SATS -> UnitId.SAT
        }
        if (unitAwareMode) {
            priceUnitOptions.firstOrNull { it.asset.unit == targetUnit }?.let {
                selectUnitOption(it)
                return
            }
        }

        currentPriceType = priceType
        selectedPriceAsset = AssetId.global(targetUnit)
        priceTypeToggle.check(
            if (priceType == PriceType.FIAT) R.id.btn_price_fiat else R.id.btn_price_bitcoin,
        )
        updatePriceInputVisibility()
        updateCurrencyDisplay()
        updateVatSectionVisibility()
    }

    /** Select an existing item's exact asset, including its custom-unit issuer. */
    fun setSelectedPriceAsset(asset: AssetId) {
        if (!unitAwareMode && (
                asset == AssetId.global(UnitId.SAT) ||
                    asset == AssetId.global(UnitId.of(currencyManager.getCurrentCurrency()))
                )
        ) {
            setCurrentPriceType(if (asset.unit.isSat) PriceType.SATS else PriceType.FIAT)
            return
        }

        if (!unitAwareMode) {
            unitAwareMode = true
            priceTypeToggle.visibility = View.GONE
            priceUnitLayout.visibility = View.VISIBLE
        }
        var option = priceUnitOptions.firstOrNull { it.asset == asset }
        if (option == null) {
            option = PriceUnitOption(
                asset = asset,
                label = unsupportedOptionLabel(asset),
                directlyChargeable = false,
            )
            priceUnitOptions.add(option)
            refreshUnitAdapter()
        }
        selectUnitOption(option)
    }

    fun getVatRate(): Int = vatRateInput.text.toString().toIntOrNull() ?: 0

    fun isVatEnabled(): Boolean = switchVatEnabled.isChecked

    fun isPriceIncludesVat(): Boolean = switchPriceIncludesVat.isChecked

    fun getEnteredFiatPrice(): Double {
        val priceString = priceInput.text.toString().trim().replace(",", ".")
        return priceString.toDoubleOrNull() ?: 0.0
    }

    fun getEnteredSatsPrice(): Long =
        satsInput.text.toString().trim().toLongOrNull() ?: 0L

    /** Parse input exactly according to the selected unit's fraction digits. */
    fun getEnteredAtomicAmount(): AtomicAmount {
        val descriptor = getSelectedUnitDescriptor()
        val rawValue = if (currentPriceType == PriceType.SATS) {
            satsInput.text.toString()
        } else {
            priceInput.text.toString()
        }.trim().replace(",", ".")
        val majorValue = rawValue.toBigDecimalOrNull() ?: BigDecimal.ZERO
        return AtomicAmount.fromMajorUnits(majorValue, selectedPriceAsset, descriptor)
    }

    fun setVatFields(vatEnabled: Boolean, vatRate: Int, priceIncludesVat: Boolean) {
        switchVatEnabled.isChecked = vatEnabled
        vatFieldsContainer.visibility = if (vatEnabled) View.VISIBLE else View.GONE
        priceBreakdownContainer.visibility = if (vatEnabled) View.VISIBLE else View.GONE
        vatRateInput.setText(vatRate.toString())
        switchPriceIncludesVat.isChecked = priceIncludesVat
        updateVatSectionVisibility()
        updatePriceBreakdown()
    }

    fun setFiatPrice(price: Double) {
        priceInput.setText(formatMajorPrice(BigDecimal.valueOf(price)))
    }

    fun setSatsPrice(sats: Long) {
        satsInput.setText(sats.toString())
    }

    fun setAtomicPrice(amount: AtomicAmount) {
        setSelectedPriceAsset(amount.asset)
        val descriptor = getSelectedUnitDescriptor()
        val displayValue = amount.toMajorUnits(descriptor)
            .setScale(descriptor.fractionDigits)
            .toPlainString()
        if (amount.unit.isSat) {
            satsInput.setText(displayValue)
        } else {
            priceInput.setText(displayValue)
        }
    }

    fun updateCurrencyDisplay() {
        val descriptor = getSelectedUnitDescriptor()
        if (!unitAwareMode && currentPriceType == PriceType.FIAT) {
            fiatPriceLayout.prefixText = currencyManager.getCurrentSymbol()
            fiatPriceLayout.suffixText = currencyManager.getCurrentCurrency()
            return
        }

        fiatPriceLayout.prefixText = descriptor.symbol.takeIf {
            it != descriptor.displayCode
        }
        fiatPriceLayout.suffixText = descriptor.displayCode
        fiatPriceLayout.hint = if (descriptor.fractionDigits == 0) "0" else {
            "0." + "0".repeat(descriptor.fractionDigits)
        }
    }

    fun isValidFiatPrice(price: String): Boolean {
        val fractionDigits = getSelectedUnitDescriptor().fractionDigits
        val pattern = if (fractionDigits == 0) {
            "^\\d+$"
        } else {
            "^(?:\\d+(?:[.,]\\d{0,$fractionDigits})?|[.,]\\d{1,$fractionDigits})$"
        }.toRegex()
        return pattern.matches(price)
    }

    fun getPriceInput(): EditText = priceInput

    fun getSatsInput(): EditText = satsInput

    private fun setupUnitSelection() {
        val context = priceInput.context
        val mintManager = MintManager.getInstance(context)
        val supportedUnits = mintManager.getSupportedUnits().filter { unit ->
            mintManager.getMintsSupportingUnit(unit.value).isNotEmpty()
        }
        val isSatOnly = supportedUnits.size == 1 && supportedUnits.single().isSat
        unitAwareMode = !isSatOnly

        if (!unitAwareMode) {
            priceUnitLayout.visibility = View.GONE
            priceUnitWarning.visibility = View.GONE
            return
        }

        supportedUnits.forEach { unit ->
            val descriptor = UnitDescriptor.defaultFor(unit)
            val supportingMints = mintManager.getMintsSupportingUnit(unit.value)
            if (descriptor.kind == UnitKind.CUSTOM) {
                supportingMints.forEach { mintUrl ->
                    priceUnitOptions.add(
                        PriceUnitOption(
                            asset = AssetId.mintScoped(unit, mintUrl),
                            label = "${descriptor.displayCode} · ${issuerLabel(mintUrl)}",
                            directlyChargeable = true,
                        ),
                    )
                }
            } else {
                priceUnitOptions.add(
                    PriceUnitOption(
                        asset = AssetId.global(unit),
                        label = descriptor.displayCode,
                        directlyChargeable = true,
                    ),
                )
            }
        }

        val currentFiat = UnitId.of(currencyManager.getCurrentCurrency())
        val hasSat = supportedUnits.any { it.isSat }
        if (hasSat && priceUnitOptions.none { it.asset.unit == currentFiat }) {
            val code = UnitDescriptor.defaultFor(currentFiat).displayCode
            priceUnitOptions.add(
                PriceUnitOption(
                    asset = AssetId.global(currentFiat),
                    label = context.getString(
                        R.string.item_entry_local_currency_conversion_label,
                        code,
                    ),
                    directlyChargeable = false,
                    convertibleToSat = true,
                ),
            )
        }

        if (priceUnitOptions.isEmpty()) {
            val fallback = UnitId.ofOrNull(mintManager.getPreferredUnit()) ?: UnitId.SAT
            priceUnitOptions.add(
                PriceUnitOption(
                    asset = AssetId.global(fallback),
                    label = unsupportedOptionLabel(AssetId.global(fallback)),
                    directlyChargeable = false,
                ),
            )
        }

        val preferredUnit = UnitId.ofOrNull(mintManager.getPreferredUnit())
        priceUnitOptions.sortWith(
            compareBy<PriceUnitOption> { if (it.asset.unit == preferredUnit) 0 else 1 }
                .thenBy { it.label },
        )
        refreshUnitAdapter()
        priceUnitLayout.visibility = View.VISIBLE
        priceTypeToggle.visibility = View.GONE
        selectUnitOption(priceUnitOptions.first())
    }

    private fun refreshUnitAdapter() {
        priceUnitInput.setAdapter(
            ArrayAdapter(
                priceUnitInput.context,
                R.layout.item_unit_dropdown,
                priceUnitOptions.map { it.label },
            ),
        )
        priceUnitInput.setOnItemClickListener { _, _, position, _ ->
            priceUnitOptions.getOrNull(position)?.let(::selectUnitOption)
        }
    }

    private fun selectUnitOption(option: PriceUnitOption) {
        selectedPriceAsset = option.asset
        currentPriceType = if (option.asset.unit.isSat) PriceType.SATS else PriceType.FIAT
        priceUnitInput.setText(option.label, false)
        updatePriceInputVisibility()
        updateCurrencyDisplay()
        updateUnitWarning(option)
        updatePriceBreakdown()
    }

    private fun updateUnitWarning(option: PriceUnitOption) {
        val warning = when {
            !option.directlyChargeable && !option.convertibleToSat ->
                R.string.item_entry_unsupported_unit_warning
            UnitDescriptor.defaultFor(option.asset.unit).kind == UnitKind.CUSTOM ->
                R.string.item_entry_custom_unit_warning
            else -> null
        }
        if (warning == null) {
            priceUnitWarning.visibility = View.GONE
        } else {
            priceUnitWarning.setText(warning)
            priceUnitWarning.visibility = View.VISIBLE
        }
    }

    private fun unsupportedOptionLabel(asset: AssetId): String {
        val context = priceInput.context
        val code = UnitDescriptor.defaultFor(asset.unit).displayCode
        val issuer = asset.issuerScope
        return if (issuer == null) {
            context.getString(R.string.item_entry_unscoped_unit_label, code)
        } else {
            "$code · ${issuerLabel(issuer)}"
        }
    }

    private fun issuerLabel(mintUrl: String): String =
        mintUrl.substringAfter("://").substringBefore('/')

    private fun setupPriceTypeToggle() {
        if (!unitAwareMode) {
            priceTypeToggle.visibility = View.VISIBLE
            priceTypeToggle.check(R.id.btn_price_fiat)
            currentPriceType = PriceType.FIAT
            selectedPriceAsset = AssetId.global(UnitId.of(currencyManager.getCurrentCurrency()))
        }

        priceTypeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || unitAwareMode) return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.btn_price_fiat -> {
                    currentPriceType = PriceType.FIAT
                    selectedPriceAsset = AssetId.global(
                        UnitId.of(currencyManager.getCurrentCurrency()),
                    )
                }
                R.id.btn_price_bitcoin -> {
                    currentPriceType = PriceType.SATS
                    selectedPriceAsset = AssetId.global(UnitId.SAT)
                }
            }
            updatePriceInputVisibility()
            updateCurrencyDisplay()
            updateVatSectionVisibility()
        }
        updatePriceInputVisibility()
    }

    private fun updatePriceInputVisibility() {
        fiatPriceLayout.visibility = if (currentPriceType == PriceType.FIAT) {
            View.VISIBLE
        } else {
            View.GONE
        }
        satsPriceLayout.visibility = if (currentPriceType == PriceType.SATS) {
            View.VISIBLE
        } else {
            View.GONE
        }
        val descriptor = getSelectedUnitDescriptor()
        priceInput.inputType = InputType.TYPE_CLASS_NUMBER or
            if (descriptor.fractionDigits > 0) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0
    }

    private fun setupVatSection() {
        vatRateInput.filters = arrayOf(InputFilter.LengthFilter(2))
        vatRateInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = updatePriceBreakdown()
        })

        switchVatEnabled.setOnCheckedChangeListener { _, isChecked ->
            vatFieldsContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
            priceBreakdownContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
            updatePriceBreakdown()
        }
        switchPriceIncludesVat.setOnCheckedChangeListener { _, _ -> updatePriceBreakdown() }
    }

    private fun updateVatSectionVisibility() {
        vatSectionCard.visibility = View.VISIBLE
        priceBreakdownContainer.visibility = if (switchVatEnabled.isChecked) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun updatePriceBreakdown() {
        if (!switchVatEnabled.isChecked) {
            priceBreakdownContainer.visibility = View.GONE
            return
        }
        priceBreakdownContainer.visibility = View.VISIBLE

        val enteredAmount = runCatching { getEnteredAtomicAmount() }
            .getOrElse { AtomicAmount.zero(selectedPriceAsset) }
        val breakdown = VatCalculator.calculateAtomicBreakdown(
            enteredAmount = enteredAmount,
            descriptor = getSelectedUnitDescriptor(),
            vatRate = getVatRate(),
            priceIncludesVat = switchPriceIncludesVat.isChecked,
        )
        textNetPrice.text = breakdown.netPrice
        textVatLabel.text = breakdown.vatLabel
        textVatAmount.text = breakdown.vatAmount
        textGrossPrice.text = breakdown.grossPrice
    }

    private fun setupInputValidation() {
        priceInput.addTextChangedListener(object : TextWatcher {
            private var current = ""

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                if (s.toString() == current) return
                priceInput.removeTextChangedListener(this)
                val original = s.toString()
                val sanitized = truncateFraction(original, getSelectedUnitDescriptor().fractionDigits)
                if (sanitized != original) {
                    priceInput.setText(sanitized)
                    priceInput.setSelection(sanitized.length)
                }
                current = priceInput.text.toString()
                priceInput.addTextChangedListener(this)
                updatePriceBreakdown()
            }
        })

        satsInput.filters = arrayOf(InputFilter { source, start, end, _, _, _ ->
            for (index in start until end) {
                if (!Character.isDigit(source[index])) return@InputFilter ""
            }
            null
        })
        satsInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = updatePriceBreakdown()
        })
    }

    private fun truncateFraction(value: String, fractionDigits: Int): String {
        val separatorIndex = value.indexOfFirst { it == '.' || it == ',' }
        if (separatorIndex < 0) return value
        if (fractionDigits == 0) return value.substring(0, separatorIndex)
        val endExclusive = minOf(value.length, separatorIndex + fractionDigits + 1)
        return value.substring(0, endExclusive)
    }

    private fun formatMajorPrice(price: BigDecimal): String {
        val fractionDigits = getSelectedUnitDescriptor().fractionDigits
        return price.setScale(fractionDigits, java.math.RoundingMode.HALF_UP).toPlainString()
    }
}
