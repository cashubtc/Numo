package com.electricdreams.numo.feature.history

import android.content.Context
import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.electricdreams.numo.R
import com.electricdreams.numo.databinding.SheetHistoryFilterBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Filter sheet for the Activity list. Every change applies immediately through [Host];
 * there is no Apply button. Picking "Custom…" asks the host for a date range.
 */
class HistoryFilterSheet : BottomSheetDialogFragment() {

    interface Host {
        val historyFilter: HistoryFilter
        fun applyHistoryFilter(filter: HistoryFilter)
        fun pickCustomDateRange()
    }

    private var binding: SheetHistoryFilterBinding? = null
    private var rendering = false

    private val host: Host? get() = activity as? Host

    override fun getTheme(): Int = R.style.Theme_Numo_BottomSheet

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = SheetHistoryFilterBinding.inflate(inflater, container, false).also { binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val b = binding ?: return

        b.statusChips.setOnCheckedStateChangeListener { _, checkedIds ->
            if (rendering) return@setOnCheckedStateChangeListener
            val status = when (checkedIds.firstOrNull()) {
                R.id.chip_status_paid -> HistoryStatusFilter.PAID
                R.id.chip_status_pending -> HistoryStatusFilter.PENDING
                else -> HistoryStatusFilter.ALL
            }
            host?.let { it.applyHistoryFilter(it.historyFilter.copy(status = status)) }
        }

        b.dateChips.setOnCheckedStateChangeListener { _, checkedIds ->
            if (rendering) return@setOnCheckedStateChangeListener
            val preset = when (checkedIds.firstOrNull()) {
                R.id.chip_date_today -> HistoryDatePreset.TODAY
                R.id.chip_date_last_7_days -> HistoryDatePreset.LAST_7_DAYS
                R.id.chip_date_last_30_days -> HistoryDatePreset.LAST_30_DAYS
                R.id.chip_date_this_month -> HistoryDatePreset.THIS_MONTH
                // Custom is handled by its click listener so it can be re-opened when selected
                R.id.chip_date_custom -> return@setOnCheckedStateChangeListener
                else -> HistoryDatePreset.ANY_TIME
            }
            host?.let { it.applyHistoryFilter(it.historyFilter.withoutDate().copy(datePreset = preset)) }
        }
        b.chipDateCustom.setOnClickListener { host?.pickCustomDateRange() }

        b.filterResetButton.setOnClickListener { host?.applyHistoryFilter(HistoryFilter()) }
        b.filterCloseButton.setOnClickListener { dismiss() }

        host?.let { render(it.historyFilter) }
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

    /** Reflects [filter] in the chips; also used to revert "Custom…" when the picker is cancelled. */
    fun render(filter: HistoryFilter) {
        val b = binding ?: return
        rendering = true
        b.statusChips.check(
            when (filter.status) {
                HistoryStatusFilter.ALL -> R.id.chip_status_all
                HistoryStatusFilter.PAID -> R.id.chip_status_paid
                HistoryStatusFilter.PENDING -> R.id.chip_status_pending
            }
        )
        b.dateChips.check(
            when (filter.datePreset) {
                HistoryDatePreset.ANY_TIME -> R.id.chip_date_any
                HistoryDatePreset.TODAY -> R.id.chip_date_today
                HistoryDatePreset.LAST_7_DAYS -> R.id.chip_date_last_7_days
                HistoryDatePreset.LAST_30_DAYS -> R.id.chip_date_last_30_days
                HistoryDatePreset.THIS_MONTH -> R.id.chip_date_this_month
                HistoryDatePreset.CUSTOM -> R.id.chip_date_custom
            }
        )
        b.chipDateCustom.text = if (filter.datePreset == HistoryDatePreset.CUSTOM) {
            filter.dateLabel(requireContext())
        } else {
            getString(R.string.history_filter_date_custom_option)
        }
        b.filterResetButton.isEnabled = filter.activeCount > 0
        b.filterResetButton.alpha = if (filter.activeCount > 0) 1f else 0.4f
        rendering = false
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "HistoryFilterSheet"
    }
}

fun HistoryStatusFilter.label(context: Context): String = context.getString(
    when (this) {
        HistoryStatusFilter.ALL -> R.string.history_filter_status_all
        HistoryStatusFilter.PAID -> R.string.history_filter_status_paid
        HistoryStatusFilter.PENDING -> R.string.history_filter_status_pending
    }
)

fun HistoryFilter.dateLabel(context: Context): String = when (datePreset) {
    HistoryDatePreset.ANY_TIME -> context.getString(R.string.history_filter_date_any)
    HistoryDatePreset.TODAY -> context.getString(R.string.history_filter_date_today)
    HistoryDatePreset.LAST_7_DAYS -> context.getString(R.string.history_filter_date_last_7_days)
    HistoryDatePreset.LAST_30_DAYS -> context.getString(R.string.history_filter_date_last_30_days)
    HistoryDatePreset.THIS_MONTH -> context.getString(R.string.history_filter_date_this_month)
    HistoryDatePreset.CUSTOM -> {
        // Picker values are UTC midnights, so format them in UTC to keep the chosen days
        val locale = Locale.getDefault()
        val format = SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "MMMd"), locale).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val start = format.format(Date(customStartUtc))
        val end = format.format(Date(customEndUtc))
        if (start == end) start else context.getString(R.string.history_filter_date_range_format, start, end)
    }
}
