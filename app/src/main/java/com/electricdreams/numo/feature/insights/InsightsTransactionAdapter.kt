package com.electricdreams.numo.feature.insights

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.electricdreams.numo.R
import com.electricdreams.numo.core.model.Amount
import java.text.SimpleDateFormat
import java.util.Locale

class InsightsTransactionAdapter(
    private var unit: DisplayUnit,
    private var fiatCurrency: Amount.Currency,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: List<TxRow> = emptyList()
    private val timeFormat = SimpleDateFormat("EEE · HH:mm", Locale.getDefault())

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
        return if (viewType == TYPE_ITEM) {
            ItemVH(inflater.inflate(R.layout.item_insights_tx_item, parent, false))
        } else {
            QuickVH(inflater.inflate(R.layout.item_insights_tx_quick, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = rows[position]
        val total = InsightsFormatter.format(unit, row.totalSats, row.totalFiatMinor, fiatCurrency)
        val meta = timeFormat.format(row.date)

        when (holder) {
            is ItemVH -> {
                val basket = row.basket!!
                holder.avatars.setItems(basket.items)
                holder.title.text = formatItemTitle(basket, holder.itemView.context)
                holder.meta.text = meta
                holder.total.text = total
            }
            is QuickVH -> {
                holder.meta.text = meta
                holder.total.text = total
            }
        }
    }

    private fun formatItemTitle(basket: BasketSummary, context: android.content.Context): String {
        val items = basket.items
        return when {
            items.size == 1 -> {
                val first = items[0]
                context.getString(R.string.insights_item_xn, first.quantity, first.itemName)
            }
            items.size == 2 -> {
                items.joinToString(", ") { context.getString(R.string.insights_item_xn, it.quantity, it.itemName) }
            }
            else -> {
                val countLabel = if (basket.totalQuantity == 1) {
                    context.getString(R.string.insights_items_count_one)
                } else {
                    context.getString(R.string.insights_items_count_other, basket.totalQuantity)
                }
                val lead = items[0]
                context.getString(R.string.insights_items_lead_with_more, countLabel, lead.itemName, items.size - 1)
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
