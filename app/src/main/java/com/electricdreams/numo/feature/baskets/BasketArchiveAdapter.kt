package com.electricdreams.numo.feature.baskets

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.electricdreams.numo.R
import com.electricdreams.numo.core.model.SavedBasket
import com.electricdreams.numo.core.util.CurrencyManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Adapter for displaying archived baskets in a RecyclerView.
 * Shows basket name, date, total, item count, and provides actions.
 */
class BasketArchiveAdapter(
    private val currencyManager: CurrencyManager,
    private val onBasketClick: (SavedBasket) -> Unit,
    private val onPaymentClick: (SavedBasket) -> Unit,
    private val onDeleteClick: (SavedBasket) -> Unit
) : RecyclerView.Adapter<BasketArchiveAdapter.ViewHolder>() {

    private var baskets: List<SavedBasket> = emptyList()
    private val dateFormat = SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.getDefault())

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val nameText: TextView = view.findViewById(R.id.basket_name)
        val dateText: TextView = view.findViewById(R.id.basket_date)
        val totalText: TextView = view.findViewById(R.id.basket_total)
        val itemCountText: TextView = view.findViewById(R.id.basket_item_count)
        val itemsSummaryText: TextView = view.findViewById(R.id.basket_items_summary)
        val viewPaymentButton: View = view.findViewById(R.id.view_payment_button)
        val deleteButton: ImageButton = view.findViewById(R.id.delete_button)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_basket_archive, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val basket = baskets[position]
        val context = holder.itemView.context

        // Display name (use position as fallback index)
        holder.nameText.text = basket.getDisplayName(position)

        // Date paid
        val date = Date(basket.paidAt ?: basket.updatedAt)
        holder.dateText.text = dateFormat.format(date)

        // Total amount
        val totalText = if (basket.hasMixedPriceTypes()) {
            val fiat = currencyManager.formatCurrencyAmount(basket.getTotalFiatPrice())
            val sats = "₿${basket.getTotalSatsPrice()}"
            "$fiat + $sats"
        } else if (basket.getTotalSatsPrice() > 0) {
            "₿${basket.getTotalSatsPrice()}"
        } else {
            currencyManager.formatCurrencyAmount(basket.getTotalFiatPrice())
        }
        holder.totalText.text = totalText

        // Item count
        val itemCount = basket.getTotalItemCount()
        holder.itemCountText.text = context.resources.getQuantityString(
            R.plurals.saved_baskets_item_count, itemCount, itemCount
        )

        // Items summary
        holder.itemsSummaryText.text = basket.getItemsSummary(3)

        // Click listeners
        holder.itemView.setOnClickListener { onBasketClick(basket) }
        holder.viewPaymentButton.setOnClickListener { onPaymentClick(basket) }
        holder.deleteButton.setOnClickListener { onDeleteClick(basket) }
    }

    override fun getItemCount(): Int = baskets.size

    fun updateBaskets(newBaskets: List<SavedBasket>) {
        baskets = newBaskets
        notifyDataSetChanged()
    }
}
