package com.electricdreams.numo.feature.insights

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.electricdreams.numo.R
import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.core.util.CurrencyManager
import com.electricdreams.numo.databinding.SheetInsightsOptionsBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * View options for Sales: the display unit and the date range, as chips in one sheet like
 * Activity's filter sheet. Every change applies immediately through [Host].
 */
class InsightsOptionsSheet : BottomSheetDialogFragment() {

    interface Host {
        val insightsUnit: DisplayUnit
        val insightsRange: InsightsRange
        fun applyInsightsUnit(unit: DisplayUnit)
        fun applyInsightsRange(range: InsightsRange)
    }

    private var binding: SheetInsightsOptionsBinding? = null
    private var rendering = false

    private val host: Host? get() = activity as? Host

    override fun getTheme(): Int = R.style.Theme_Numo_BottomSheet

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = SheetInsightsOptionsBinding.inflate(inflater, container, false).also { binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val b = binding ?: return

        b.chipUnitFiat.text = Amount.Currency.fromCode(
            CurrencyManager.getInstance(requireContext()).getCurrentCurrency()
        ).name

        b.unitChips.setOnCheckedStateChangeListener { _, checkedIds ->
            if (rendering) return@setOnCheckedStateChangeListener
            val unit = if (checkedIds.firstOrNull() == R.id.chip_unit_sats) DisplayUnit.SATS else DisplayUnit.FIAT
            host?.applyInsightsUnit(unit)
        }

        b.rangeChips.setOnCheckedStateChangeListener { _, checkedIds ->
            if (rendering) return@setOnCheckedStateChangeListener
            val range = when (checkedIds.firstOrNull()) {
                R.id.chip_range_weeks -> InsightsRange.WEEK
                R.id.chip_range_months -> InsightsRange.MONTH
                else -> InsightsRange.DAY
            }
            host?.applyInsightsRange(range)
        }

        b.optionsCloseButton.setOnClickListener { dismiss() }

        host?.let { render(it.insightsUnit, it.insightsRange) }
    }

    override fun onStart() {
        super.onStart()
        // Same dismissal as the app's other sheets: opens fully, one swipe down closes it
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            isFitToContents = true
            skipCollapsed = true
            isDraggable = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    private fun render(unit: DisplayUnit, range: InsightsRange) {
        val b = binding ?: return
        rendering = true
        b.unitChips.check(if (unit == DisplayUnit.SATS) R.id.chip_unit_sats else R.id.chip_unit_fiat)
        b.rangeChips.check(
            when (range) {
                InsightsRange.DAY -> R.id.chip_range_days
                InsightsRange.WEEK -> R.id.chip_range_weeks
                InsightsRange.MONTH -> R.id.chip_range_months
            }
        )
        rendering = false
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "InsightsOptionsSheet"
    }
}
