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
import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.core.util.CurrencyManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class CurrencyOptionsSheet : BottomSheetDialogFragment() {

    private var current: DisplayUnit = DisplayUnit.FIAT
    private var onSelected: ((DisplayUnit) -> Unit)? = null

    fun configure(current: DisplayUnit, onSelected: (DisplayUnit) -> Unit) {
        this.current = current
        this.onSelected = onSelected
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.sheet_currency_options, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val fiatLabel = Amount.Currency.fromCode(
            CurrencyManager.getInstance(requireContext()).getCurrentCurrency()
        ).name

        val options = listOf(
            DisplayUnit.FIAT to fiatLabel,
            DisplayUnit.SATS to getString(R.string.insights_currency_sats),
        )

        val recycler = view.findViewById<RecyclerView>(R.id.currency_recycler)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = Adapter(options, current) { picked ->
            onSelected?.invoke(picked)
            dismiss()
        }
    }

    private class Adapter(
        private val options: List<Pair<DisplayUnit, String>>,
        private val current: DisplayUnit,
        private val onClick: (DisplayUnit) -> Unit,
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
            val (unit, name) = options[position]
            holder.label.text = name
            holder.check.visibility = if (unit == current) View.VISIBLE else View.INVISIBLE
            holder.itemView.setOnClickListener { onClick(unit) }
        }
    }

    companion object {
        const val TAG = "CurrencyOptionsSheet"
    }
}
