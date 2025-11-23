package com.electricdreams.shellshock.ui.adapter

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.electricdreams.shellshock.R
import com.electricdreams.shellshock.core.data.model.TokenHistoryEntry
import java.text.SimpleDateFormat
import java.util.Locale

class TokenHistoryAdapter : RecyclerView.Adapter<TokenHistoryAdapter.ViewHolder>() {

    fun interface OnDeleteClickListener {
        fun onDeleteClick(entry: TokenHistoryEntry, position: Int)
    }

    private val entries: MutableList<TokenHistoryEntry> = mutableListOf()
    private val dateFormat = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault())
    private var onDeleteClickListener: OnDeleteClickListener? = null

    fun setOnDeleteClickListener(listener: OnDeleteClickListener) {
        onDeleteClickListener = listener
    }

    fun setEntries(newEntries: List<TokenHistoryEntry>) {
        entries.clear()
        entries.addAll(newEntries)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_token_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = entries[position]

        holder.amountText.text = String.format(Locale.getDefault(), "%d ₿", entry.amount)
        holder.dateText.text = dateFormat.format(entry.date)

        holder.copyButton.setOnClickListener { v ->
            val context = v.context
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Token", entry.token)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "Token copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        holder.openWithButton.setOnClickListener { v -> openTokenWithApp(v.context, entry.token) }

        holder.deleteButton.setOnClickListener {
            onDeleteClickListener?.onDeleteClick(entry, position)
        }
    }

    private fun openTokenWithApp(context: Context, token: String) {
        val cashuUri = "cashu:$token"

        val uriIntent = Intent(Intent.ACTION_VIEW, Uri.parse(cashuUri)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, cashuUri)
        }

        val chooserIntent = Intent.createChooser(uriIntent, "Open token with...").apply {
            putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(shareIntent))
        }

        try {
            context.startActivity(chooserIntent)
        } catch (e: Exception) {
            Toast.makeText(context, "No apps available to handle this token", Toast.LENGTH_SHORT).show()
        }
    }

    override fun getItemCount(): Int = entries.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val amountText: TextView = view.findViewById(R.id.amount_text)
        val dateText: TextView = view.findViewById(R.id.date_text)
        val copyButton: ImageButton = view.findViewById(R.id.copy_button)
        val openWithButton: ImageButton = view.findViewById(R.id.open_with_button)
        val deleteButton: ImageButton = view.findViewById(R.id.delete_button)
    }
}
