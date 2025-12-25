package com.electricdreams.numo.feature.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.electricdreams.numo.R
import com.electricdreams.numo.core.data.model.ErrorLogEntry
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * RecyclerView adapter for displaying error log entries in the developer
 * Error Logs screen.
 */
class ErrorLogsAdapter(
    private val onItemClick: (ErrorLogEntry) -> Unit,
) : ListAdapter<ErrorLogEntry, ErrorLogsAdapter.ViewHolder>(DiffCallback) {

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.row_error_log_entry, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = getItem(position)
        holder.bind(entry)
        holder.itemView.setOnClickListener { onItemClick(entry) }
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val timeView: TextView = itemView.findViewById(R.id.error_log_time)
        private val tagView: TextView = itemView.findViewById(R.id.error_log_tag)
        private val messageView: TextView = itemView.findViewById(R.id.error_log_message)

        private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        fun bind(entry: ErrorLogEntry) {
            timeView.text = timeFormat.format(entry.timestamp)
            tagView.text = entry.tag
            messageView.text = entry.message
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<ErrorLogEntry>() {
        override fun areItemsTheSame(oldItem: ErrorLogEntry, newItem: ErrorLogEntry): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ErrorLogEntry, newItem: ErrorLogEntry): Boolean {
            return oldItem == newItem
        }
    }
}
