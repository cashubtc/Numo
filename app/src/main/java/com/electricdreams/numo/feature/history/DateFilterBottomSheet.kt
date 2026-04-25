package com.electricdreams.numo.feature.history

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import com.electricdreams.numo.R
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import java.util.Calendar

class DateFilterBottomSheet : BottomSheetDialogFragment() {

    override fun getTheme(): Int = R.style.Theme_Numo_BottomSheet

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.bottom_sheet_date_filter, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val currentStart = arguments?.getLong(ARG_CURRENT_START, 0L) ?: 0L
        val currentEnd = arguments?.getLong(ARG_CURRENT_END, 0L) ?: 0L
        if (currentStart == 0L && currentEnd == 0L) {
            view.findViewById<View>(R.id.check_all_time).visibility = View.VISIBLE
        }

        view.findViewById<View>(R.id.row_all_time).setOnClickListener { send(0L, 0L) }
        view.findViewById<View>(R.id.row_last_7).setOnClickListener {
            val (s, e) = preset(daysBack = 7)
            send(s, e)
        }
        view.findViewById<View>(R.id.row_last_30).setOnClickListener {
            val (s, e) = preset(daysBack = 30)
            send(s, e)
        }
        view.findViewById<View>(R.id.row_this_month).setOnClickListener {
            send(startOfThisMonthLocal(), System.currentTimeMillis())
        }
        view.findViewById<View>(R.id.row_custom).setOnClickListener {
            send(SENTINEL_CUSTOM, 0L)
        }

        setupBottomSheetBehavior()
    }

    private fun setupBottomSheetBehavior() {
        dialog?.setOnShowListener { dialogInterface ->
            val bottomSheetDialog = dialogInterface as BottomSheetDialog
            val bottomSheet = bottomSheetDialog.findViewById<FrameLayout>(
                com.google.android.material.R.id.design_bottom_sheet
            ) ?: return@setOnShowListener
            bottomSheet.setBackgroundColor(
                ContextCompat.getColor(requireContext(), R.color.color_bg_white)
            )
            BottomSheetBehavior.from(bottomSheet).apply {
                state = BottomSheetBehavior.STATE_EXPANDED
                skipCollapsed = true
                isDraggable = true
            }
        }
    }

    private fun send(start: Long, end: Long) {
        setFragmentResult(
            RESULT_KEY,
            bundleOf(RESULT_START to start, RESULT_END to end)
        )
        dismiss()
    }

    private fun preset(daysBack: Int): Pair<Long, Long> {
        val now = System.currentTimeMillis()
        val start = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, -daysBack)
            set(Calendar.HOUR_OF_DAY, 0)
            clear(Calendar.MINUTE)
            clear(Calendar.SECOND)
            clear(Calendar.MILLISECOND)
        }.timeInMillis
        return start to now
    }

    private fun startOfThisMonthLocal(): Long = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        clear(Calendar.MINUTE)
        clear(Calendar.SECOND)
        clear(Calendar.MILLISECOND)
    }.timeInMillis

    companion object {
        const val TAG = "DateFilterBottomSheet"
        const val RESULT_KEY = "date_filter_result"
        const val RESULT_START = "start"
        const val RESULT_END = "end"
        const val SENTINEL_CUSTOM = -1L

        private const val ARG_CURRENT_START = "current_start"
        private const val ARG_CURRENT_END = "current_end"

        fun newInstance(currentStart: Long, currentEnd: Long): DateFilterBottomSheet =
            DateFilterBottomSheet().apply {
                arguments = bundleOf(
                    ARG_CURRENT_START to currentStart,
                    ARG_CURRENT_END to currentEnd,
                )
            }
    }
}
