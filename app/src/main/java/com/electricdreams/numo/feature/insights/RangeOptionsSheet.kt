package com.electricdreams.numo.feature.insights

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.electricdreams.numo.R
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class RangeOptionsSheet : BottomSheetDialogFragment() {

    private var current: InsightsRange = InsightsRange.DAY
    private var onSelected: ((InsightsRange) -> Unit)? = null

    fun configure(current: InsightsRange, onSelected: (InsightsRange) -> Unit) {
        this.current = current
        this.onSelected = onSelected
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.sheet_range_options, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val options = listOf(
            InsightsRange.DAY to getString(R.string.insights_range_days),
            InsightsRange.WEEK to getString(R.string.insights_range_weeks),
            InsightsRange.MONTH to getString(R.string.insights_range_months),
        )

        val recycler = view.findViewById<RecyclerView>(R.id.range_recycler)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = Adapter(options, current) { picked ->
            onSelected?.invoke(picked)
            dismiss()
        }
    }

    private class Adapter(
        private val options: List<Pair<InsightsRange, String>>,
        private val current: InsightsRange,
        private val onClick: (InsightsRange) -> Unit,
    ) : RecyclerView.Adapter<Adapter.VH>() {

        class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val label: TextView = itemView.findViewById(R.id.option_label)
            val check: ImageView = itemView.findViewById(R.id.option_check)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_insights_option, parent, false)
            return VH(v)
        }

        override fun getItemCount() = options.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val (range, name) = options[position]
            holder.label.text = name
            holder.check.visibility = if (range == current) View.VISIBLE else View.INVISIBLE
            holder.itemView.setOnClickListener { onClick(range) }
        }
    }

    companion object {
        const val TAG = "RangeOptionsSheet"
    }
}
