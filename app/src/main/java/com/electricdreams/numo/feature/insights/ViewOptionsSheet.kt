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
    private var onRangeChanged: ((InsightsRange) -> Unit)? = null
    private var currentUnit: DisplayUnit = DisplayUnit.FIAT
    private var currentRange: InsightsRange = InsightsRange.DAY

    fun configure(
        currentUnit: DisplayUnit,
        currentRange: InsightsRange,
        onUnitChanged: (DisplayUnit) -> Unit,
        onRangeChanged: (InsightsRange) -> Unit,
    ) {
        this.currentUnit = currentUnit
        this.currentRange = currentRange
        this.onUnitChanged = onUnitChanged
        this.onRangeChanged = onRangeChanged
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.sheet_view_options, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val unitValue = view.findViewById<TextView>(R.id.row_show_me_in_value)
        unitValue.text = labelFor(currentUnit)

        view.findViewById<View>(R.id.row_show_me_in).setOnClickListener {
            CurrencyOptionsSheet().apply {
                configure(currentUnit) { newUnit ->
                    onUnitChanged?.invoke(newUnit)
                    this@ViewOptionsSheet.dismiss()
                }
            }.show(parentFragmentManager, CurrencyOptionsSheet.TAG)
        }

        val rangeValue = view.findViewById<TextView>(R.id.row_date_range_value)
        rangeValue.text = labelFor(currentRange)

        view.findViewById<View>(R.id.row_date_range).setOnClickListener {
            RangeOptionsSheet().apply {
                configure(currentRange) { newRange ->
                    onRangeChanged?.invoke(newRange)
                    this@ViewOptionsSheet.dismiss()
                }
            }.show(parentFragmentManager, RangeOptionsSheet.TAG)
        }
    }

    private fun labelFor(unit: DisplayUnit): String = when (unit) {
        DisplayUnit.FIAT -> Amount.Currency.fromCode(
            CurrencyManager.getInstance(requireContext()).getCurrentCurrency()
        ).name
        DisplayUnit.SATS -> getString(R.string.insights_currency_sats)
    }

    private fun labelFor(range: InsightsRange): String = getString(
        when (range) {
            InsightsRange.DAY -> R.string.insights_range_days
            InsightsRange.WEEK -> R.string.insights_range_weeks
            InsightsRange.MONTH -> R.string.insights_range_months
        }
    )

    companion object {
        const val TAG = "ViewOptionsSheet"
    }
}
