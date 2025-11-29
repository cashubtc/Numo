package com.electricdreams.numo.feature.baskets

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.electricdreams.numo.R
import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.core.model.SavedBasket
import com.electricdreams.numo.core.util.CurrencyManager

/**
 * Adapter for displaying saved baskets in a RecyclerView.
 */
class SavedBasketsAdapter(
    private val currencyManager: CurrencyManager,
    private val onBasketClick: (SavedBasket) -> Unit,
    private val onRenameClick: (SavedBasket) -> Unit,
    private val onDeleteClick: (SavedBasket) -> Unit
) : RecyclerView.Adapter<SavedBasketsAdapter.BasketViewHolder>() {

    private var baskets: List<SavedBasket> = emptyList()

    fun updateBaskets(newBaskets: List<SavedBasket>) {
        val diffCallback = BasketDiffCallback(baskets, newBaskets)
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        baskets = newBaskets
        diffResult.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BasketViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_saved_basket, parent, false)
        return BasketViewHolder(view)
    }

    override fun onBindViewHolder(holder: BasketViewHolder, position: Int) {
        holder.bind(baskets[position], position)
    }

    override fun getItemCount(): Int = baskets.size

    inner class BasketViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val basketContent: View = itemView.findViewById(R.id.basket_content)
        private val basketName: TextView = itemView.findViewById(R.id.basket_name)
        private val basketSummary: TextView = itemView.findViewById(R.id.basket_summary)
        private val basketMeta: TextView = itemView.findViewById(R.id.basket_meta)
        private val basketTotal: TextView = itemView.findViewById(R.id.basket_total)
        private val editNameButton: TextView = itemView.findViewById(R.id.edit_name_button)
        private val deleteButton: TextView = itemView.findViewById(R.id.delete_button)

        fun bind(basket: SavedBasket, position: Int) {
            val context = itemView.context

            // Name
            basketName.text = basket.getDisplayName(position)

            // Items summary
            val summary = basket.getItemsSummary(2)
            basketSummary.text = summary
            basketSummary.visibility = if (summary.isNotEmpty()) View.VISIBLE else View.GONE

            // Meta info (item count + time)
            val itemCount = basket.getTotalItemCount()
            val itemCountText = context.resources.getQuantityString(
                R.plurals.saved_baskets_item_count,
                itemCount,
                itemCount
            )
            val timeAgo = DateUtils.getRelativeTimeSpanString(
                basket.updatedAt,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE
            )
            basketMeta.text = context.getString(R.string.saved_baskets_meta_format, itemCountText, timeAgo)

            // Total price
            basketTotal.text = formatTotal(basket)

            // Click listeners
            basketContent.setOnClickListener { onBasketClick(basket) }
            editNameButton.setOnClickListener { onRenameClick(basket) }
            deleteButton.setOnClickListener { onDeleteClick(basket) }
        }

        private fun formatTotal(basket: SavedBasket): String {
            val fiatTotal = basket.getTotalFiatPrice()
            val satsTotal = basket.getTotalSatsPrice()
            val currencyCode = currencyManager.getCurrentCurrency()
            val currency = Amount.Currency.fromCode(currencyCode)

            return when {
                fiatTotal > 0 && satsTotal > 0 -> {
                    val fiatAmount = Amount.fromMajorUnits(fiatTotal, currency)
                    val satsAmount = Amount(satsTotal, Amount.Currency.BTC)
                    "$fiatAmount + $satsAmount"
                }
                satsTotal > 0 -> Amount(satsTotal, Amount.Currency.BTC).toString()
                fiatTotal > 0 -> Amount.fromMajorUnits(fiatTotal, currency).toString()
                else -> Amount.fromMajorUnits(0.0, currency).toString()
            }
        }
    }

    private class BasketDiffCallback(
        private val oldList: List<SavedBasket>,
        private val newList: List<SavedBasket>
    ) : DiffUtil.Callback() {
        override fun getOldListSize(): Int = oldList.size
        override fun getNewListSize(): Int = newList.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition].id == newList[newItemPosition].id
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val old = oldList[oldItemPosition]
            val new = newList[newItemPosition]
            return old.name == new.name &&
                    old.items == new.items &&
                    old.updatedAt == new.updatedAt
        }
    }
}
