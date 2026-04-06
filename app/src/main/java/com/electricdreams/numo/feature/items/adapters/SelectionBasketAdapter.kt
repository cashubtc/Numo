package com.electricdreams.numo.feature.items.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.electricdreams.numo.R
import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.core.model.BasketItem
import com.electricdreams.numo.core.util.CurrencyManager

/**
 * Adapter for displaying basket items in the item selection screen.
 * Shows compact basket item rows with remove functionality.
 */
class SelectionBasketAdapter(
    private val currencyManager: CurrencyManager,
    private val onItemRemoved: (String) -> Unit
) : RecyclerView.Adapter<SelectionBasketAdapter.BasketViewHolder>() {

    private var basketItems: List<BasketItem> = emptyList()

    fun updateItems(newItems: List<BasketItem>) {
        val oldItems = basketItems
        val diffResult = DiffUtil.calculateDiff(BasketDiffCallback(oldItems, newItems))
        basketItems = newItems
        diffResult.dispatchUpdatesTo(this)
    }

    private class BasketDiffCallback(
        private val oldList: List<BasketItem>,
        private val newList: List<BasketItem>
    ) : DiffUtil.Callback() {
        // Items are displayed in reverse order, so map adapter positions accordingly
        override fun getOldListSize(): Int = oldList.size
        override fun getNewListSize(): Int = newList.size

        override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
            val oldItem = oldList[oldList.size - 1 - oldPos]
            val newItem = newList[newList.size - 1 - newPos]
            return oldItem.item.id == newItem.item.id
        }

        override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
            val oldItem = oldList[oldList.size - 1 - oldPos]
            val newItem = newList[newList.size - 1 - newPos]
            return oldItem == newItem
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BasketViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_basket_compact, parent, false)
        return BasketViewHolder(view)
    }

    override fun onBindViewHolder(holder: BasketViewHolder, position: Int) {
        // Show newest first (reverse order)
        val basketItem = basketItems[basketItems.size - 1 - position]
        holder.bind(basketItem)
    }

    override fun getItemCount(): Int = basketItems.size

    inner class BasketViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val quantityBadge: TextView = itemView.findViewById(R.id.item_quantity_badge)
        private val nameView: TextView = itemView.findViewById(R.id.item_name)
        private val variationView: TextView = itemView.findViewById(R.id.item_variation)
        private val totalView: TextView = itemView.findViewById(R.id.item_total)
        private val removeButton: ImageButton = itemView.findViewById(R.id.remove_button)

        fun bind(basketItem: BasketItem) {
            val item = basketItem.item

            quantityBadge.text = basketItem.quantity.toString()
            nameView.text = item.name ?: ""

            if (!item.variationName.isNullOrEmpty()) {
                variationView.text = item.variationName
                variationView.visibility = View.VISIBLE
            } else {
                variationView.visibility = View.GONE
            }

            // Format total using unified Amount class
            totalView.text = if (basketItem.isSatsPrice()) {
                Amount(basketItem.getTotalSats(), Amount.Currency.BTC).toString()
            } else {
                val currencyCode = currencyManager.getCurrentCurrency()
                val currency = Amount.Currency.fromCode(currencyCode)
                Amount.fromMajorUnits(basketItem.getTotalPrice(), currency).toString()
            }

            removeButton.setOnClickListener {
                val itemId = item.id!!
                onItemRemoved(itemId)
            }
        }
    }
}
