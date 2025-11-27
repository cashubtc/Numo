package com.electricdreams.shellshock.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.electricdreams.shellshock.R
import com.electricdreams.shellshock.core.data.model.PaymentHistoryEntry
import com.electricdreams.shellshock.core.model.Amount
import java.text.SimpleDateFormat
import java.util.Locale

class PaymentsHistoryAdapter : RecyclerView.Adapter<PaymentsHistoryAdapter.ViewHolder>() {

    fun interface OnItemClickListener {
        fun onItemClick(entry: PaymentHistoryEntry, position: Int)
    }

    private val entries: MutableList<PaymentHistoryEntry> = mutableListOf()
    private val dateFormat = SimpleDateFormat("MMM d", Locale.getDefault())
    private var onItemClickListener: OnItemClickListener? = null

    fun setOnItemClickListener(listener: OnItemClickListener) {
        onItemClickListener = listener
    }

    fun setEntries(newEntries: List<PaymentHistoryEntry>) {
        entries.clear()
        entries.addAll(newEntries)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_payment_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = entries[position]
        val context = holder.itemView.context

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
            isPending -> "Pending Payment"
            entry.isLightning() -> "Lightning Payment"
            entry.isCashu() -> "Cashu Payment"
            entry.amount > 0 -> "Cash In"
            else -> "Cash Out"
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
            holder.statusText.text = "Tap to resume"
            holder.statusText.setTextColor(context.getColor(R.color.color_warning))
        } else {
            holder.statusText.visibility = View.GONE
        }

        // Hide subtitle (payment type already shown in title)
        holder.subtitleText.visibility = View.GONE

        holder.itemView.setOnClickListener {
            onItemClickListener?.onItemClick(entry, position)
        }
    }

    override fun getItemCount(): Int = entries.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val amountText: TextView = view.findViewById(R.id.amount_text)
        val dateText: TextView = view.findViewById(R.id.date_text)
        val titleText: TextView = view.findViewById(R.id.title_text)
        val subtitleText: TextView = view.findViewById(R.id.subtitle_text)
        val statusText: TextView = view.findViewById(R.id.status_text)
        val icon: ImageView = view.findViewById(R.id.icon)
    }
}
