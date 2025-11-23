package com.electricdreams.shellshock.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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

        // Display amount in the unit it was entered
        val formattedAmount = if (entry.getEntryUnit() != "sat") {
            val entryCurrency = Amount.Currency.fromCode(entry.getEntryUnit())
            val entryAmount = Amount(entry.enteredAmount, entryCurrency)
            entryAmount.toString()
        } else {
            val satAmount = Amount(entry.amount, Amount.Currency.BTC)
            satAmount.toString()
        }

        val displayAmount = if (entry.amount >= 0) "+$formattedAmount" else formattedAmount
        holder.amountText.text = displayAmount

        // Set date
        holder.dateText.text = dateFormat.format(entry.date)

        // Set title based on amount (simple logic for now)
        holder.titleText.text = if (entry.amount > 0) "Cash In" else "Cash Out"

        holder.itemView.setOnClickListener {
            onItemClickListener?.onItemClick(entry, position)
        }
    }

    override fun getItemCount(): Int = entries.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val amountText: TextView = view.findViewById(R.id.amount_text)
        val dateText: TextView = view.findViewById(R.id.date_text)
        val titleText: TextView = view.findViewById(R.id.title_text)
    }
}
