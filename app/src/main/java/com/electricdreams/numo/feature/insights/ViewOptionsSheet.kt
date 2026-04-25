package com.electricdreams.numo.feature.insights

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.electricdreams.numo.R
import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.core.util.CurrencyManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class ViewOptionsSheet : BottomSheetDialogFragment() {

    private var onUnitChanged: ((DisplayUnit) -> Unit)? = null
    private var currentUnit: DisplayUnit = DisplayUnit.FIAT

    fun configure(currentUnit: DisplayUnit, onUnitChanged: (DisplayUnit) -> Unit) {
        this.currentUnit = currentUnit
        this.onUnitChanged = onUnitChanged
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.sheet_view_options, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val valueView = view.findViewById<TextView>(R.id.row_show_me_in_value)
        valueView.text = labelFor(currentUnit)

        view.findViewById<View>(R.id.row_show_me_in).setOnClickListener {
            CurrencyOptionsSheet().apply {
                configure(currentUnit) { newUnit ->
                    currentUnit = newUnit
                    valueView.text = labelFor(newUnit)
                    onUnitChanged?.invoke(newUnit)
                }
            }.show(parentFragmentManager, CurrencyOptionsSheet.TAG)
        }
    }

    private fun labelFor(unit: DisplayUnit): String = when (unit) {
        DisplayUnit.FIAT -> Amount.Currency.fromCode(
            CurrencyManager.getInstance(requireContext()).getCurrentCurrency()
        ).name
        DisplayUnit.SATS -> getString(R.string.insights_currency_sats)
        DisplayUnit.BTC -> getString(R.string.insights_currency_btc)
    }

    companion object {
        const val TAG = "ViewOptionsSheet"
    }
}
