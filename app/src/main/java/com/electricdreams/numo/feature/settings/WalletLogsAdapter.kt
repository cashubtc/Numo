package com.electricdreams.numo.feature.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.electricdreams.numo.R
import com.electricdreams.numo.core.data.model.WalletLogEntry
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * RecyclerView adapter for displaying wallet activity log entries in the developer
 * Wallet Logs screen.
 */
class WalletLogsAdapter(
    private val onItemClick: (WalletLogEntry) -> Unit,
) : ListAdapter<WalletLogEntry, WalletLogsAdapter.ViewHolder>(DiffCallback) {

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.row_wallet_log_entry, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = getItem(position)
        holder.bind(entry)
        holder.itemView.setOnClickListener { onItemClick(entry) }
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val timeView: TextView = itemView.findViewById(R.id.wallet_log_time)
        private val directionView: TextView = itemView.findViewById(R.id.wallet_log_direction)
        private val amountView: TextView = itemView.findViewById(R.id.wallet_log_amount)
        private val messageView: TextView = itemView.findViewById(R.id.wallet_log_message)

        private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        fun bind(entry: WalletLogEntry) {
            timeView.text = timeFormat.format(entry.timestamp)
            directionView.text = entry.direction
            amountView.text = "${entry.amount} sats"
            messageView.text = entry.message
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<WalletLogEntry>() {
        override fun areItemsTheSame(oldItem: WalletLogEntry, newItem: WalletLogEntry): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: WalletLogEntry, newItem: WalletLogEntry): Boolean {
            return oldItem == newItem
        }
    }
}
