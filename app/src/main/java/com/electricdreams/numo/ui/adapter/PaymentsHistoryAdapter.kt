package com.electricdreams.numo.ui.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.electricdreams.numo.R
import com.electricdreams.numo.core.data.model.PaymentHistoryEntry
import com.electricdreams.numo.core.model.Amount
import java.text.SimpleDateFormat
import java.util.Locale

class PaymentsHistoryAdapter : RecyclerView.Adapter<PaymentsHistoryAdapter.ViewHolder>() {

    fun interface OnItemClickListener {
        fun onItemClick(entry: PaymentHistoryEntry, position: Int)
    }

    fun interface OnItemDeleteListener {
        fun onItemDelete(entry: PaymentHistoryEntry, position: Int)
    }

    private val entries: MutableList<PaymentHistoryEntry> = mutableListOf()
    private val dateFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
    
    private var onItemClickListener: OnItemClickListener? = null
    private var onItemDeleteListener: OnItemDeleteListener? = null
    
    private var openItemPosition: Int = RecyclerView.NO_POSITION

    fun setOnItemClickListener(listener: OnItemClickListener) {
        onItemClickListener = listener
    }

    fun setOnItemDeleteListener(listener: OnItemDeleteListener) {
        onItemDeleteListener = listener
    }

    fun setEntries(newEntries: List<PaymentHistoryEntry>) {
        openItemPosition = RecyclerView.NO_POSITION
        entries.clear()
        entries.addAll(newEntries)
        notifyDataSetChanged()
    }

    fun closeOpenItem() {
        val previousOpen = openItemPosition
        if (previousOpen != RecyclerView.NO_POSITION) {
            openItemPosition = RecyclerView.NO_POSITION
            notifyItemChanged(previousOpen, "close")
        }
    }

    private fun getDeleteWidth(context: Context): Float {
        return 80f * context.resources.displayMetrics.density
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_payment_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains("close")) {
            holder.mainContent.animate()
                .translationX(0f)
                .setDuration(250)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = entries[position]
        val context = holder.itemView.context

        // Reset translation immediately without animation to prevent recycled views from staying open
        holder.mainContent.translationX = if (position == openItemPosition) -getDeleteWidth(context) else 0f

        holder.mainContent.setOnLongClickListener {
            if (openItemPosition != position) {
                // Close previously open item if needed
                val previousOpen = openItemPosition
                openItemPosition = position
                if (previousOpen != RecyclerView.NO_POSITION) {
                    notifyItemChanged(previousOpen, "close")
                }
                
                // Animate this item open
                holder.mainContent.animate()
                    .translationX(-getDeleteWidth(context))
                    .setDuration(250)
                    .setInterpolator(android.view.animation.OvershootInterpolator(1f))
                    .start()
            }
            true // Consume event
        }

        holder.mainContent.setOnClickListener {
            if (openItemPosition == position) {
                // If it's open, a regular click should just close it
                closeOpenItem()
            } else {
                if (openItemPosition != RecyclerView.NO_POSITION) {
                    closeOpenItem()
                } else {
                    onItemClickListener?.onItemClick(entry, position)
                }
            }
        }

        holder.deleteButtonContainer.setOnClickListener {
            closeOpenItem()
            onItemDeleteListener?.onItemDelete(entry, position)
        }

        // Display amount in the unit it was entered
        // Use BASE amount (excluding tip) for proper accounting display
        val formattedAmount = if (entry.getEntryUnit() != "sat") {
            // Fiat entry - enteredAmount is already the base amount
            val entryCurrency = Amount.Currency.fromCode(entry.getEntryUnit())
            val entryAmount = Amount(entry.enteredAmount, entryCurrency)
            entryAmount.toString()
        } else {
            // Sat entry - use base amount (excluding tip)
            val baseAmountSats = entry.getBaseAmountSats()
            val satAmount = Amount(baseAmountSats, Amount.Currency.BTC)
            satAmount.toString()
        }

        // Set amount with appropriate prefix
        val isPending = entry.isPending()
        val displayAmount = if (isPending) {
            formattedAmount // No + prefix for pending
        } else if (entry.amount >= 0) {
            "+$formattedAmount"
        } else {
            formattedAmount
        }
        holder.amountText.text = displayAmount

        // Set date
        holder.dateText.text = dateFormat.format(entry.date)

        // Set title based on status and payment type
        holder.titleText.text = when {
            isPending -> context.getString(R.string.history_row_title_pending_payment)
            entry.isLightning() -> context.getString(R.string.history_row_title_lightning_payment)
            entry.isCashu() -> context.getString(R.string.history_row_title_cashu_payment)
            entry.amount > 0 -> context.getString(R.string.history_row_title_cash_in)
            else -> context.getString(R.string.history_row_title_cash_out)
        }

        // Set icon based on payment type and status
        val iconRes = when {
            isPending -> R.drawable.ic_pending
            entry.isLightning() -> R.drawable.ic_lightning_bolt
            else -> R.drawable.ic_bitcoin
        }
        holder.icon.setImageResource(iconRes)

        // Set icon tint based on status
        val iconTint = if (isPending) {
            context.getColor(R.color.color_warning)
        } else {
            context.getColor(R.color.color_text_primary)
        }
        holder.icon.setColorFilter(iconTint)

        // Show status for pending payments
        if (isPending) {
            holder.statusText.visibility = View.VISIBLE
            holder.statusText.text = context.getString(R.string.history_row_status_tap_to_resume)
            holder.statusText.setTextColor(context.getColor(R.color.color_warning))
        } else {
            holder.statusText.visibility = View.GONE
        }

        // Hide subtitle (payment type already shown in title)
        holder.subtitleText.visibility = View.GONE
    }

    override fun getItemCount(): Int = entries.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val mainContent: View = view.findViewById(R.id.main_content)
        val deleteButtonContainer: View = view.findViewById(R.id.delete_button_container)
        val amountText: TextView = view.findViewById(R.id.amount_text)
        val dateText: TextView = view.findViewById(R.id.date_text)
        val titleText: TextView = view.findViewById(R.id.title_text)
        val subtitleText: TextView = view.findViewById(R.id.subtitle_text)
        val statusText: TextView = view.findViewById(R.id.status_text)
        val icon: ImageView = view.findViewById(R.id.icon)
    }
}
