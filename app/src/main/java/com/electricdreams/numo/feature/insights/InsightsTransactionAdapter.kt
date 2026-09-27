package com.electricdreams.numo.feature.insights

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.electricdreams.numo.R
import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.ui.util.TransactionDates

class InsightsTransactionAdapter(
    private var unit: DisplayUnit,
    private var fiatCurrency: Amount.Currency,
    private val onRowClick: (TxRow) -> Unit = {},
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: List<TxRow> = emptyList()

    fun submit(rows: List<TxRow>, unit: DisplayUnit, fiatCurrency: Amount.Currency) {
        this.rows = rows
        this.unit = unit
        this.fiatCurrency = fiatCurrency
        notifyDataSetChanged()
    }

    override fun getItemCount() = rows.size

    override fun getItemViewType(position: Int): Int =
        if (rows[position].basket != null) TYPE_ITEM else TYPE_QUICK

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val holder = if (viewType == TYPE_ITEM) {
            ItemVH(inflater.inflate(R.layout.item_insights_tx_item, parent, false))
        } else {
            QuickVH(inflater.inflate(R.layout.item_insights_tx_quick, parent, false))
        }
        holder.itemView.setOnClickListener {
            val position = holder.bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) onRowClick(rows[position])
        }
        return holder
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = rows[position]
        val total = InsightsFormatter.format(unit, row.totalSats, row.totalFiatMinor, fiatCurrency)
        val meta = TransactionDates.row(holder.itemView.context, row.date)

        when (holder) {
            is ItemVH -> {
                val basket = row.basket!!
                holder.avatars.setItems(basket.items)
                holder.title.text = SaleSummaries.title(holder.itemView.context, basket)
                holder.meta.text = meta
                holder.total.text = total
            }
            is QuickVH -> {
                holder.meta.text = meta
                holder.total.text = total
            }
        }
    }

    private class ItemVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val avatars: StackedAvatarsView = itemView.findViewById(R.id.avatars)
        val title: TextView = itemView.findViewById(R.id.tx_title)
        val meta: TextView = itemView.findViewById(R.id.tx_meta)
        val total: TextView = itemView.findViewById(R.id.tx_total)
    }

    private class QuickVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val meta: TextView = itemView.findViewById(R.id.tx_meta)
        val total: TextView = itemView.findViewById(R.id.tx_total)
    }

    companion object {
        private const val TYPE_ITEM = 0
        private const val TYPE_QUICK = 1
    }
}
